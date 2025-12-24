import * as vscode from 'vscode';
import * as path from 'path';
import { State, FolderState, FileState } from '../types';
import { ConfigService } from './ConfigService';
import { Logger } from '../logger';
import { IdeMetaData } from '../IdeMetaData';
import { Debouncer } from '../utils/Debouncer';
import { PathUtils } from '../utils/PathUtils';

import { StateHelper } from './StateHelper';

/**
 * Service responsible for connecting the IDE events with the synchronization logic.
 * It listens for user activities (file selection, edits) and captures/applies the IDE state.
 */
export class IdeConnector {
    private configService: ConfigService;
    private onUserActivityCallback: (() => void) | null = null;
    private disposables: vscode.Disposable[] = [];
    private isApplyingState: boolean = false;
    // Debouncer for applying state (received from other IDEs) -> Inbound
    private applyStateDebouncer = new Debouncer(300);

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
     * Skips if currently applying state.
     */
    private triggerActivity() {
        if (this.isApplyingState) return;

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

        const activeEditor = vscode.window.activeTextEditor;
        const activePath = activeEditor?.document.uri.fsPath;

        const openedFiles: FileState[] = [];
        const tabs: vscode.Tab[] = vscode.window.tabGroups.all.flatMap(group => group.tabs);

        for (const tab of tabs) {
            if (tab.input instanceof vscode.TabInputText) {
                const fsPath = tab.input.uri.fsPath;
                if (this.configService.shouldSyncFile(fsPath)) {
                    let cursor = 0;
                    let column = 0;

                    const fsPathLower = PathUtils.normalizePath(fsPath);
                    const editor = vscode.window.visibleTextEditors.find(e => PathUtils.normalizePath(e.document.uri.fsPath) === fsPathLower);

                    if (editor) {
                        cursor = editor.selection.active.line;
                        column = editor.selection.active.character;
                    }

                    openedFiles.push({
                        filePath: fsPath,
                        cursor: cursor,
                        column: column,
                        isActive: false // will be recalculated in buildState
                    });
                }
            }
        }

        return StateHelper.buildState(rootPath, openedFiles, activePath);
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
                const filesToOpen: FileState[] = [];
                const rootPath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;

                if (!rootPath) {
                    Logger.log(`Skipping apply state: No workspace folder found`);
                    return;
                }

                // 1. Check intersection
                // If the current project root has no relationship with the state root, we should not apply the state.
                if (!StateHelper.hasIntersection(state, rootPath)) {
                    Logger.log(`Skipping apply state: Project path ${rootPath} has no intersection with state root ${state.root.path}`);
                    return;
                }

                filesToOpen.push(...StateHelper.getFiles(state, rootPath));

                const currentTabs = vscode.window.tabGroups.all.flatMap(group => group.tabs);
                const currentFiles = new Map<string, vscode.Tab>();

                for (const tab of currentTabs) {
                    if (tab.input instanceof vscode.TabInputText) {
                        currentFiles.set(tab.input.uri.fsPath, tab);
                    }
                }

                // 2. Close files not in state
                // We only close files that are part of the project but not in the new state.
                for (const [fsPath, tab] of currentFiles) {
                    // Check if file belongs to current project root AND the incoming state's scope
                    // If the file is outside the scope of the incoming state (e.g. syncing a subfolder),
                    // we should not close it as the incoming state has no authority over it.
                    if (!StateHelper.isInsideRoot(rootPath, fsPath)
                        || !StateHelper.checkPathBelongsToState(state, fsPath)) {
                        continue;
                    }

                    const keep = filesToOpen.some(f => PathUtils.normalizePath(f.filePath) === PathUtils.normalizePath(fsPath));
                    if (!keep) {
                        Logger.log(`Closing file: ${fsPath}`);
                        await vscode.window.tabGroups.close(tab);
                    }
                }

                // 3. Open or Update files
                // Iterate through files in the state and open/activate/scroll them.
                for (const fileState of filesToOpen) {
                    try {
                        const fsPath = fileState.filePath;
                        const uri = vscode.Uri.file(fsPath);

                        // 3.1 Open if need open
                        let editor = this.getTextEditor(fsPath);
                        const isActive = PathUtils.normalizePath(vscode.window.activeTextEditor?.document.uri.fsPath || '') === PathUtils.normalizePath(fsPath);
                        const isOpened = Array.from(currentFiles.keys()).some(k => PathUtils.normalizePath(k) === PathUtils.normalizePath(fsPath));

                        if ((!isOpened && !editor) || (fileState.isActive && !isActive)) {
                            Logger.log(`Opening/Updating file: ${fsPath}`);
                            const doc = await vscode.workspace.openTextDocument(uri);
                            editor = await vscode.window.showTextDocument(doc, {
                                preview: false,
                                preserveFocus: !fileState.isActive,
                                viewColumn: editor?.viewColumn
                            });
                        }

                        // 3.2 Move caret if need move
                        if (editor && fileState.cursor >= 0) {
                            const currentCursor = editor.selection.active;
                            if (currentCursor.line !== fileState.cursor || currentCursor.character !== (fileState.column || 0)) {
                                Logger.log(`Moving cursor to ${fileState.cursor}:${fileState.column} in ${fsPath}`);
                                const newPos = new vscode.Position(fileState.cursor, fileState.column || 0);
                                editor.selection = new vscode.Selection(newPos, newPos);
                                editor.revealRange(new vscode.Range(newPos, newPos), vscode.TextEditorRevealType.InCenter);
                            }
                        }

                    } catch (e) {
                        Logger.error(`Failed to sync file ${fileState.filePath}:`, e);
                    }
                }

            } catch (e) {
                Logger.error('Error applying state', e);
            } finally {
                this.isApplyingState = false;
            }
        });
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
