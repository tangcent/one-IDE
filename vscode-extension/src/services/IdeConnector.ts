import * as vscode from 'vscode';
import * as path from 'path';
import { State, FolderState, FileState } from '../types';
import { ConfigService } from './ConfigService';
import { Logger } from '../logger';
import { IdeMetaData } from '../IdeMetaData';

export class IdeConnector {
    private configService: ConfigService;
    private onUserActivityCallback: (() => void) | null = null;
    private disposables: vscode.Disposable[] = [];
    private isApplyingState: boolean = false;

    constructor(configService: ConfigService) {
        this.configService = configService;
        this.watchEditor();
    }

    public setOnUserActivity(callback: () => void) {
        this.onUserActivityCallback = callback;
    }

    public isWindowFocused(): boolean {
        return vscode.window.state.focused;
    }

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

    private triggerActivity() {
        if (this.isApplyingState) return;

        if (this.onUserActivityCallback) {
            this.onUserActivityCallback();
        }
    }

    public async captureState(): Promise<State> {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        const rootPath = workspaceFolders && workspaceFolders.length > 0 ? workspaceFolders[0].uri.fsPath : '';

        const rootNode: FolderState = {
            path: rootPath,
            openedFiles: [],
            subFolders: []
        };

        const activeEditor = vscode.window.activeTextEditor;
        const activePath = activeEditor?.document.uri.fsPath;

        const findOrCreateFolder = (fullPath: string): FolderState => {
            if (!fullPath.startsWith(rootPath)) return rootNode;
            if (fullPath === rootPath) return rootNode;

            const relative = path.relative(rootPath, fullPath);
            const parts = relative.split(path.sep);

            let current = rootNode;
            let currentPath = rootPath;

            for (const part of parts) {
                if (!part) continue;
                currentPath = path.join(currentPath, part);
                let next = current.subFolders.find(f => f.path === currentPath);
                if (!next) {
                    next = {
                        path: currentPath,
                        openedFiles: [],
                        subFolders: []
                    };
                    current.subFolders.push(next);
                }
                current = next;
            }
            return current;
        };

        const tabs: vscode.Tab[] = vscode.window.tabGroups.all.flatMap(group => group.tabs);

        for (const tab of tabs) {
            if (tab.input instanceof vscode.TabInputText) {
                const fsPath = tab.input.uri.fsPath;
                if (this.configService.shouldSyncFile(fsPath)) {
                    const dirPath = path.dirname(fsPath);
                    const folderNode = findOrCreateFolder(dirPath);

                    let cursor = 0;
                    let column = 0;
                    const editor = vscode.window.visibleTextEditors.find(e => e.document.uri.fsPath === fsPath);
                    if (editor) {
                        cursor = editor.selection.active.line;
                        column = editor.selection.active.character;
                    }

                    folderNode.openedFiles.push({
                        filePath: fsPath,
                        cursor: cursor,
                        column: column,
                        isActive: fsPath === activePath
                    });

                    if (fsPath === activePath) {
                        folderNode.activeFile = fsPath;
                    }
                }
            }
        }

        const meta = IdeMetaData.getInstance();
        return {
            timestamp: Date.now(),
            source: meta.id,
            ide: 'vscode',
            root: rootNode
        };
    }

    public async applyState(state: State) {
        Logger.log(`Applying state from ${state.source}`);
        this.isApplyingState = true;

        try {
            const filesToOpen: FileState[] = [];
            let activeFileToSet: string | undefined;
            const rootPath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
            const normalizedRoot = rootPath ? path.resolve(rootPath) : undefined;
            if (!normalizedRoot) {
                Logger.log(`Skipping apply state: No workspace folder found`);
                return;
            }

            const stateRoot = path.resolve(state.root.path);

            // 2. Check intersection
            const normalizedStateRoot = path.resolve(stateRoot);
            const hasIntersection = normalizedRoot.startsWith(normalizedStateRoot) || normalizedStateRoot.startsWith(normalizedRoot);
            if (!hasIntersection) {
                Logger.log(`Skipping apply state: Project path ${normalizedRoot} has no intersection with state root ${normalizedStateRoot}`);
                return;
            }

            const traverse = (node: FolderState) => {
                if (node.openedFiles) {
                    if (normalizedRoot) {
                        const filtered = node.openedFiles.filter(f => {
                            try {
                                const fPath = path.resolve(f.filePath);
                                return fPath.startsWith(normalizedRoot) || normalizedRoot.startsWith(fPath);
                            } catch (e) {
                                return false;
                            }
                        });
                        filesToOpen.push(...filtered);
                    } else {
                        filesToOpen.push(...node.openedFiles);
                    }
                }
                if (node.activeFile) activeFileToSet = node.activeFile;
                if (node.subFolders) {
                    node.subFolders.forEach(traverse);
                }
            };

            traverse(state.root);

            const currentTabs = vscode.window.tabGroups.all.flatMap(group => group.tabs);
            const currentFiles = new Map<string, vscode.Tab>();

            for (const tab of currentTabs) {
                if (tab.input instanceof vscode.TabInputText) {
                    currentFiles.set(tab.input.uri.fsPath, tab);
                }
            }

            // 1. Close files not in state
            for (const [fsPath, tab] of currentFiles) {
                if (normalizedRoot) {
                    const relative = path.relative(normalizedRoot, fsPath);
                    const isInside = !relative.startsWith('..') && !path.isAbsolute(relative);
                    if (!isInside) continue;
                }

                const keep = filesToOpen.some(f => f.filePath === fsPath);
                if (!keep) {
                    Logger.log(`Closing file: ${fsPath}`);
                    await vscode.window.tabGroups.close(tab);
                }
            }

            // 2. Open or Update files
            for (const fileState of filesToOpen) {
                try {
                    const fsPath = fileState.filePath;
                    const uri = vscode.Uri.file(fsPath);

                    // 1. Open if need open
                    let editor = this.getTextEditor(fsPath);
                    const isActive = vscode.window.activeTextEditor?.document.uri.fsPath === fsPath;
                    const isOpened = currentFiles.has(fsPath);

                    if ((!isOpened && !editor) || (fileState.isActive && !isActive)) {
                        Logger.log(`Opening/Updating file: ${fsPath}`);
                        const doc = await vscode.workspace.openTextDocument(uri);
                        editor = await vscode.window.showTextDocument(doc, {
                            preview: false,
                            preserveFocus: !fileState.isActive,
                            viewColumn: editor?.viewColumn
                        });
                    }

                    // 2. Move caret if need move
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
    }

    private getTextEditor(fsPath: string): vscode.TextEditor | undefined {
        return vscode.window.visibleTextEditors.find(e => e.document.uri.fsPath === fsPath);
    }

    public dispose() {
        this.disposables.forEach(d => d.dispose());
        this.disposables = [];
    }
}
