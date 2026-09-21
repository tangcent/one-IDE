import * as vscode from 'vscode';
import * as path from 'path';
import { State, ActiveFile, NodeInfo } from '../types';
import { ConfigService } from './ConfigService';
import { Logger } from '../logger';
import { IdeMetaData } from '../IdeMetaData';
import { Debouncer } from '../utils/Debouncer';
import { PathUtils } from '../utils/PathUtils';

import { StateHelper } from './StateHelper';

/**
 * Service responsible for connecting the Ide events with the synchronization logic.
 * It listens for user activities (file selection, edits) and captures/applies the Ide state.
 */
export class IdeConnector {
    private configService: ConfigService;
    private onUserActivityCallback: (() => void) | null = null;
    private disposables: vscode.Disposable[] = [];
    private isApplyingState: boolean = false;
    // Debouncer for applying state (received from other IDEs) -> Inbound
    private applyStateDebouncer = new Debouncer(300);

    private isShowingVersionWarning = false;

    constructor(configService: ConfigService) {
        this.configService = configService;
        this.watchEditor();
    }

    /**
     * Sets the callback to be invoked when user activity occurs.
     * @param callback The callback function
     */
    public setOnUserActivity(callback: () => void) {
        this.onUserActivityCallback = callback;
    }

    /**
     * Checks if the IDE window is currently focused.
     * @returns True if the window is focused
     */
    public isWindowFocused(): boolean {
        return vscode.window.state.focused;
    }

    /**
     * Sets up listeners for file editor events.
     */
    private watchEditor() {
        this.disposables.push(
            vscode.window.onDidChangeActiveTextEditor(() => this.triggerActivity()),
            vscode.window.onDidChangeTextEditorSelection(() => this.triggerActivity()),
            vscode.window.onDidChangeVisibleTextEditors(() => this.triggerActivity()),
            vscode.workspace.onDidOpenTextDocument(() => this.triggerActivity()),
            vscode.workspace.onDidCloseTextDocument(() => this.triggerActivity()),
            vscode.window.onDidChangeWindowState((e) => {
                if (e.focused) {
                    this.triggerActivity();
                }
            })
        );
    }

    /**
     * Triggers the user activity callback.
     * Skips if currently applying state or if there are unsaved/dirty documents
     * (which may indicate AI tools are making edits).
     */
    private triggerActivity() {
        if (this.isApplyingState) return;

        // Skip triggering activity if there are dirty diff editors open
        // This prevents interference with AI coding tools that use diff editors for approval
        const hasDirtyDiffEditors = vscode.window.tabGroups.all
            .flatMap(group => group.tabs)
            .some(tab => tab.input instanceof vscode.TabInputTextDiff && tab.isDirty);
        
        if (hasDirtyDiffEditors) {
            return;
        }

        if (this.onUserActivityCallback) {
            this.onUserActivityCallback();
        }
    }


    /**
     * Captures the current state of the IDE (opened files, active file, cursor positions).
     * @returns Promise resolving to the current State
     */
    public async captureState(): Promise<State> {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        const rootPath = workspaceFolders && workspaceFolders.length > 0 ? workspaceFolders[0].uri.fsPath : '';

        // 1. Capture Editor State
        const editorState = await this.captureEditorState();

        return StateHelper.buildState(
            rootPath, 
            editorState.openedFiles, 
            editorState.activeFile
        );
    }

    private async captureEditorState(): Promise<{ openedFiles: string[], activeFile: ActiveFile | undefined }> {
        const activeEditor = vscode.window.activeTextEditor;
        const activePath = activeEditor?.document.uri.fsPath;

        const openedFiles: string[] = [];
        let activeFile: ActiveFile | undefined = undefined;

        const tabs: vscode.Tab[] = vscode.window.tabGroups.all.flatMap(group => group.tabs);

        for (const tab of tabs) {
            if (tab.input instanceof vscode.TabInputText) {
                const fsPath = tab.input.uri.fsPath;
                if (this.configService.shouldSyncFile(fsPath)) {
                    openedFiles.push(fsPath);

                    if (activePath && PathUtils.normalizePath(fsPath) === PathUtils.normalizePath(activePath)) {
                        let cursor = 0;
                        let column = 0;
                        let selectionEndCursor: number | undefined;
                        let selectionEndColumn: number | undefined;

                        if (activeEditor) {
                            if (!activeEditor.selection.isEmpty) {
                                // Use start as cursor/column
                                cursor = activeEditor.selection.start.line;
                                column = activeEditor.selection.start.character;
                                
                                selectionEndCursor = activeEditor.selection.end.line;
                                selectionEndColumn = activeEditor.selection.end.character;
                            } else {
                                cursor = activeEditor.selection.active.line;
                                column = activeEditor.selection.active.character;
                            }
                        }
                        activeFile = {
                            filePath: fsPath,
                            cursor: cursor,
                            column: column,
                            selectionEndCursor: selectionEndCursor,
                            selectionEndColumn: selectionEndColumn
                        };
                    }
                }
            }
        }
        return { openedFiles, activeFile };
    }

    /**
     * Applies the received state to the IDE.
     * This involves opening files, closing irrelevant files, and moving the cursor.
     * @param state The state to apply
     */
    public async applyState(state: State) {
        this.applyStateDebouncer.debounce(async () => {
            Logger.log(`Applying state from ${state.source}`);
            this.isApplyingState = true;

            try {
                const rootPath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;

                if (!rootPath) {
                    Logger.log(`Skipping apply state: No workspace folder found`);
                    return;
                }

                // 1. Check intersection
                // If the current project root has no relationship with the state root, we should not apply the state.
                if (!StateHelper.hasIntersection(state, rootPath)) {
                    Logger.log(`Skipping apply state: Project path ${rootPath} has no intersection with state root ${state.root}`);
                    return;
                }

                // 2. Apply Editor State
                await this.applyEditorState(state, rootPath);

            } catch (e) {
                Logger.error('Error applying state', e);
            } finally {
                this.isApplyingState = false;
            }
        });
    }

    private async applyEditorState(state: State, rootPath: string) {
        // Only focus/activate the mirrored active file when this IDE window is already focused.
        // Otherwise (e.g. the user is editing in the other IDE on Windows) focusing would steal
        // OS window focus back to this IDE, causing a focus ping-pong between the two IDEs.
        const windowFocused = this.isWindowFocused();

        const filesToOpen = StateHelper.getFiles(state, rootPath);
        const activeFile = StateHelper.getActiveFile(state, rootPath);

        const currentTabs = vscode.window.tabGroups.all.flatMap(group => group.tabs);
        const currentFiles = new Map<string, vscode.Tab>();

        for (const tab of currentTabs) {
            if (tab.input instanceof vscode.TabInputText) {
                currentFiles.set(tab.input.uri.fsPath, tab);
            }
        }

        // Close files not in state
        for (const [fsPath, tab] of currentFiles) {
            // Check if file belongs to current project root AND the incoming state's scope
            // If the file is outside the scope of the incoming state (e.g. syncing a subfolder),
            // we should not close it as the incoming state has no authority over it.
            if (!StateHelper.isInsideRoot(rootPath, fsPath)
                || !StateHelper.checkPathBelongsToState(state, fsPath)) {
                continue;
            }

            // Skip tabs that are dirty (unsaved) or in a diff editor — these may be
            // pending edits from AI coding tools (e.g. Claude Code, Copilot) and closing
            // them would abort the edit operation.
            if (tab.isDirty || tab.input instanceof vscode.TabInputTextDiff) {
                Logger.log(`Skipping close for modified/diff tab: ${fsPath}`);
                continue;
            }

            const keep = filesToOpen.some(f => PathUtils.normalizePath(f) === PathUtils.normalizePath(fsPath));
            if (!keep) {
                Logger.log(`Closing file: ${fsPath}`);
                await vscode.window.tabGroups.close(tab);
            }
        }

        // Open files
        for (const fsPath of filesToOpen) {
            // If this file is the active file, we can skip opening it here
            // because it will be opened and activated later.
            if (activeFile && PathUtils.normalizePath(fsPath) === PathUtils.normalizePath(activeFile.filePath)) {
                continue;
            }

            try {
                const uri = vscode.Uri.file(fsPath);
                const isOpened = Array.from(currentFiles.keys()).some(k => PathUtils.normalizePath(k) === PathUtils.normalizePath(fsPath));

                if (!isOpened) {
                    Logger.log(`Opening file: ${fsPath}`);
                    const doc = await vscode.workspace.openTextDocument(uri);
                    await vscode.window.showTextDocument(doc, {
                        preview: false,
                        preserveFocus: true
                    });
                }
            } catch (e) {
                Logger.error(`Failed to sync file ${fsPath}:`, e);
            }
        }

        // Activate file and move cursor
        if (activeFile) {
            try {
                const fsPath = activeFile.filePath;
                const uri = vscode.Uri.file(fsPath);
                Logger.log(`Activating file: ${fsPath}`);

                const doc = await vscode.workspace.openTextDocument(uri);
                const editor = await vscode.window.showTextDocument(doc, {
                    preview: false,
                    preserveFocus: !windowFocused
                });

                if (editor && activeFile.cursor >= 0) {
                    const startPos = new vscode.Position(activeFile.cursor, activeFile.column || 0);

                    if (activeFile.selectionEndCursor !== undefined && activeFile.selectionEndColumn !== undefined) {
                        const endPos = new vscode.Position(activeFile.selectionEndCursor, activeFile.selectionEndColumn);
                        // Check if selection needs update
                        if (!editor.selection.start.isEqual(startPos) || !editor.selection.end.isEqual(endPos)) {
                                Logger.log(`Setting selection to ${activeFile.cursor}:${activeFile.column} - ${activeFile.selectionEndCursor}:${activeFile.selectionEndColumn}`);
                                editor.selection = new vscode.Selection(startPos, endPos);
                                editor.revealRange(new vscode.Range(startPos, endPos), vscode.TextEditorRevealType.InCenter);
                        }
                    } else {
                        const currentCursor = editor.selection.active;
                        if (currentCursor.line !== activeFile.cursor || currentCursor.character !== (activeFile.column || 0) || !editor.selection.isEmpty) {
                            Logger.log(`Moving cursor to ${activeFile.cursor}:${activeFile.column} in ${fsPath}`);
                            editor.selection = new vscode.Selection(startPos, startPos);
                            editor.revealRange(new vscode.Range(startPos, startPos), vscode.TextEditorRevealType.InCenter);
                        }
                    }
                }
            } catch (e) {
                Logger.error(`Failed to activate file ${activeFile.filePath}:`, e);
            }
        }
    }

    public checkPluginVersion(remoteNode: NodeInfo | undefined) {
        const remoteVersion = remoteNode?.pluginVersion;
        const currentVersion = IdeMetaData.getInstance().pluginVersion;
        if (remoteVersion !== currentVersion) {
            if (this.isShowingVersionWarning) return;

            const remoteIde = remoteNode?.ide ? ` (${remoteNode.ide})` : 'Other IDE';
            const message = `One-IDE Plugin Version Mismatch. ${remoteIde}: ${remoteVersion}, Local: ${currentVersion}`;

            // Simple semver comparison logic since we don't have semver package
            // Assuming format x.y.z
            const isRemoteNewer = remoteVersion != null && this.compareVersions(remoteVersion, currentVersion) > 0;

            if (isRemoteNewer) {
                this.isShowingVersionWarning = true;
                vscode.window.showWarningMessage(message, 'Update Extension').then(selection => {
                    this.isShowingVersionWarning = false;
                    if (selection === 'Update Extension') {
                        vscode.env.openExternal(vscode.Uri.parse('vscode:extension/tangcent.one-ide'));
                    }
                });
            } else {
                this.isShowingVersionWarning = true;
                vscode.window.showWarningMessage(message).then(() => {
                    this.isShowingVersionWarning = false;
                });
            }
        }
    }

    private compareVersions(v1: string, v2: string): number {
        const parts1 = v1.split('.').map(Number);
        const parts2 = v2.split('.').map(Number);

        for (let i = 0; i < Math.max(parts1.length, parts2.length); i++) {
            const num1 = parts1[i] || 0;
            const num2 = parts2[i] || 0;
            if (num1 > num2) return 1;
            if (num1 < num2) return -1;
        }
        return 0;
    }

    private getTextEditor(fsPath: string): vscode.TextEditor | undefined {
        const normalized = PathUtils.normalizePath(fsPath);
        return vscode.window.visibleTextEditors.find(e => PathUtils.normalizePath(e.document.uri.fsPath) === normalized);
    }

    public dispose() {
        this.disposables.forEach(d => d.dispose());
        this.disposables = [];
        this.applyStateDebouncer.cancel();
    }
}
