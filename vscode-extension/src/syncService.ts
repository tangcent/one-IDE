import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { IdeMetaData } from './IdeMetaData';
import { State, FolderState, FileState } from './types';
import { ConfigService } from './services/ConfigService';
import { StateService } from './services/StateService';
import { Logger } from './logger';
import { Debouncer } from './utils/Debouncer';

const ONE_IDE_DIR = path.join(os.homedir(), '.one-ide');

export class SyncService implements vscode.Disposable {
    private sourceId: string;
    private isEnabled: boolean = true;
    private statusBarItem: vscode.StatusBarItem;

    private configService!: ConfigService;
    private stateService!: StateService;

    private pendingState: State | null = null;
    private isProcessingQueue: boolean = false;

    private postDebouncer = new Debouncer(300);
    private applyDebouncer = new Debouncer(300);

    private disposables: vscode.Disposable[] = [];

    constructor(configService: ConfigService) {
        const meta = IdeMetaData.getInstance();
        this.sourceId = meta.id;
        Logger.setMetaData(meta);
        Logger.log(`Source ID: ${this.sourceId}`);

        try {
            this.ensureOneIdeDir();
            this.configService = configService;
            this.stateService = new StateService(
                ONE_IDE_DIR, 
                this.handleStateChange.bind(this),
                () => vscode.workspace.workspaceFolders?.[0]?.uri.fsPath
            );
        } catch (e) {
            Logger.error('Initialization error:', e);
            this.isEnabled = false;
        }

        this.statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
        this.statusBarItem.command = 'one-ide.toggle';
        this.updateStatusBar();
        this.statusBarItem.show();
    }

    private ensureOneIdeDir() {
        if (!fs.existsSync(ONE_IDE_DIR)) {
            fs.mkdirSync(ONE_IDE_DIR);
        }
    }

    public toggleSync() {
        this.isEnabled = !this.isEnabled;
        this.updateStatusBar();
        vscode.window.showInformationMessage(`One-IDE Sync: ${this.isEnabled ? 'Enabled' : 'Disabled'}`);
    }

    private updateStatusBar() {
        if (this.isEnabled) {
            this.statusBarItem.text = '$(sync) One-IDE: On';
            this.statusBarItem.tooltip = 'Click to disable synchronization';
        } else {
            this.statusBarItem.text = '$(sync-ignored) One-IDE: Off';
            this.statusBarItem.tooltip = 'Click to enable synchronization';
        }
    }

    public async start() {
        Logger.log(`Starting SyncService. SourceID: ${this.sourceId}`);
        this.watchEditor();
        // Initial sync
        this.triggerUpdate();
    }

    private watchEditor() {
        this.disposables.push(
            vscode.workspace.onDidChangeTextDocument(() => { /* Content change, maybe ignore */ }),
            vscode.window.onDidChangeActiveTextEditor(() => this.triggerUpdate()),
            vscode.window.onDidChangeTextEditorSelection(() => this.triggerUpdate(true)),
            vscode.window.onDidChangeVisibleTextEditors(() => this.triggerUpdate()),
            vscode.workspace.onDidOpenTextDocument(() => this.triggerUpdate()),
            vscode.workspace.onDidCloseTextDocument(() => this.triggerUpdate())
        );
    }

    private triggerUpdate(isCursor: boolean = false) {
        if (!this.isEnabled || this.stateService.isSyncing()) return

        // Background Check:
        // If window is not focused, and the last sync (from others) was less than 5s ago,
        // we assume this event is a delayed echo or side-effect of the sync, so we ignore it.
        if (!vscode.window.state.focused) {
            const timeSinceLastSync = Date.now() - this.stateService.getLastCheckPoint();
            if (timeSinceLastSync < 5000) {
                // Logger.log(`Ignoring background event (Last sync: ${timeSinceLastSync}ms ago)`);
                return;
            }
        }

        const delay = isCursor ? 300 : 100;
        this.postDebouncer.debounce(() => {
            this.generateAndSaveState();
        }, delay);
    }

    private async generateAndSaveState() {
        if (this.stateService.isSyncing()) return

        const state = await this.buildState();

        this.stateService.appendState(state);
    }

    private async buildState(): Promise<State> {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        const rootPath = workspaceFolders && workspaceFolders.length > 0 ? workspaceFolders[0].uri.fsPath : '';

        const rootNode: FolderState = {
            path: rootPath,
            openedFiles: [],
            subFolders: []
        };

        const activeEditor = vscode.window.activeTextEditor;
        const activePath = activeEditor?.document.uri.fsPath;

        // Helper to find or create folder node
        const findOrCreateFolder = (fullPath: string): FolderState => {
            if (!fullPath.startsWith(rootPath)) return rootNode; // Should not happen if filtered
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

        // Gather all open tabs
        const tabs: vscode.Tab[] = vscode.window.tabGroups.all.flatMap(group => group.tabs);

        for (const tab of tabs) {
            if (tab.input instanceof vscode.TabInputText) {
                const fsPath = tab.input.uri.fsPath;
                if (this.configService.shouldSyncFile(fsPath)) {
                    // Find folder
                    const dirPath = path.dirname(fsPath);
                    const folderNode = findOrCreateFolder(dirPath);

                    // Get cursor info if visible
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

        return {
            timestamp: Date.now(),
            source: this.sourceId,
            ide: 'vscode',
            root: rootNode
        };
    }

    private async handleStateChange(state: State) {
        if (!this.isEnabled || state.source === this.sourceId) {
            Logger.log(`Ignoring state from ${state.source} (self)`);
            return;
        }

        // Always update pending state to the latest one
        this.pendingState = state;

        // Debounce the processing
        if (!this.isProcessingQueue) {
            this.applyDebouncer.debounce(() => this.processQueue());
        }
    }

    private async processQueue() {
        if (this.isProcessingQueue) return;
        this.isProcessingQueue = true;

        try {
            while (this.pendingState) {
                // Atomic-like: grab state and clear pending
                const state = this.pendingState;
                this.pendingState = null;

                await this.applyState(state);
            }
        } finally {
            this.isProcessingQueue = false;
            // Double check if new state arrived while we were finishing
            if (this.pendingState) {
                // Let's just recurse to drain the queue (or latest item).
                this.processQueue();
            }
        }
    }

    private async applyState(state: State) {
        Logger.log(`Received state from ${state.source}`);
        Logger.log('Start syncing...');
        this.stateService.startSync();

        try {
            // Flatten state to list of files to open
            const filesToOpen: FileState[] = [];
            let activeFileToSet: string | undefined;
            const rootPath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
            const normalizedRoot = rootPath ? path.resolve(rootPath) : undefined;

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
                    await vscode.window.tabGroups.close(tab);
                }
            }

            // 2. Open or Update files
            for (const fileState of filesToOpen) {
                try {
                    const fsPath = fileState.filePath;
                    const isOpen = currentFiles.has(fsPath);

                    // Optimization: Check if update is needed
                    let needUpdate = true;
                    if (isOpen) {
                        // Check if cursor/active state matches
                        const editor = vscode.window.visibleTextEditors.find(e => e.document.uri.fsPath === fsPath);
                        if (editor) {
                            const currentCursor = editor.selection.active;

                            // Check if we need to switch active editor
                            const isActive = vscode.window.activeTextEditor?.document.uri.fsPath === fsPath;

                            if (fileState.isActive && isActive) {
                                // Already active. Check cursor
                                if (fileState.cursor >= 0 && currentCursor.line === fileState.cursor && currentCursor.character === (fileState.column || 0)) {
                                    needUpdate = false;
                                }
                            } else if (!fileState.isActive && !isActive) {
                                // Not active and shouldn't be. If cursor matches (if visible), skip.
                                if (fileState.cursor >= 0 && currentCursor.line === fileState.cursor && currentCursor.character === (fileState.column || 0)) {
                                    needUpdate = false;
                                }
                            }
                        }
                    }

                    if (!needUpdate) continue;

                    const uri = vscode.Uri.file(fsPath);
                    // Use showTextDocument to open/focus
                    const doc = await vscode.workspace.openTextDocument(uri);
                    const editor = await vscode.window.showTextDocument(doc, {
                        preview: false,
                        preserveFocus: !fileState.isActive
                    });

                    // Set cursor
                    if (fileState.cursor >= 0) {
                        const newPos = new vscode.Position(fileState.cursor, fileState.column || 0);
                        editor.selection = new vscode.Selection(newPos, newPos);
                        editor.revealRange(new vscode.Range(newPos, newPos), vscode.TextEditorRevealType.InCenter);
                    }

                } catch (e) {
                    Logger.error(`Failed to sync file ${fileState.filePath}:`, e);
                }
            }

            // Final pass: Ensure active file is actually active
            // (Sometimes iterating and opening files shifts focus to the last opened one)
            if (activeFileToSet) {
                const activeEditor = vscode.window.activeTextEditor;
                if (activeEditor?.document.uri.fsPath !== activeFileToSet) {
                    const uri = vscode.Uri.file(activeFileToSet);
                    try {
                        const doc = await vscode.workspace.openTextDocument(uri);
                        await vscode.window.showTextDocument(doc, { preview: false, preserveFocus: false });
                    } catch (e) {
                        // ignore
                    }
                }
            }

        } catch (e) {
            Logger.error('Error applying state', e);
        } finally {
            Logger.log('Sync completed.');
            // Short delay to prevent echo
            // VS Code events might be fired with a delay after the async operations complete.
            // We need a longer timeout (1000ms) compared to JetBrains (100ms) because 
            // JetBrains runs on the EDT where events are serialized more strictly.
            setTimeout(() => {
                this.stateService.endSync();
            }, 1000);
        }
    }

    public dispose() {
        this.isEnabled = false;
        this.statusBarItem.dispose();
        this.postDebouncer.cancel();
        this.applyDebouncer.cancel();
        this.disposables.forEach(d => d.dispose());
        this.disposables = [];
        this.stateService.dispose();
    }
}
