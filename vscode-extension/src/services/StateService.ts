import * as fs from 'fs';
import * as path from 'path';
import { State, FolderState, FileState } from '../types';
import { Logger } from '../logger';
import { IdeMetaData } from '../IdeMetaData';

export class StateService {
    private stateFile: string;
    private processingCount: number = 0;
    private onStateChanged: (state: State) => void;
    private lastFileSize: number = 0;
    private watcher: fs.FSWatcher | null = null;

    constructor(
        oneIdeDir: string, 
        onStateChanged: (state: State) => void,
        private getCurrentProjectPath: () => string | undefined
    ) {
        this.stateFile = path.join(oneIdeDir, 'state.json');
        this.onStateChanged = onStateChanged;
        this.init();
    }

    public dispose() {
        if (this.watcher) {
            this.watcher.close();
            this.watcher = null;
        }
    }

    private init() {
        if (!fs.existsSync(this.stateFile)) {
            // Optional: Create if not exists, or just wait for write
        } else {
            this.lastFileSize = fs.statSync(this.stateFile).size;
            this.readLatestState();
        }
        this.watchStateFile();
    }

    public startSync() {
        this.processingCount++;
    }

    public endSync() {
        this.processingCount = Math.max(0, this.processingCount - 1);
    }

    public isSyncing(): boolean {
        return this.processingCount > 0;
    }

    public getLastCheckPoint(): number {
        return IdeMetaData.getInstance().lastCheckPoint;
    }

    /**
     * Reads state file and returns [BestMatchingState, MaxTimestamp]
     */
    private readStateFromFile(): [State | null, number] {
        if (!fs.existsSync(this.stateFile)) return [null, 0];
        try {
            const content = fs.readFileSync(this.stateFile, 'utf-8');
            const lines = content.trim().split('\n');
            if (lines.length === 0) return [null, 0];

            const currentPath = this.getCurrentProjectPath();
            const meta = IdeMetaData.getInstance();
            let maxTimestamp = 0;
            let bestState: State | null = null;

            for (let i = lines.length - 1; i >= 0; i--) {
                const line = lines[i].trim();
                if (!line) continue;

                try {
                    const state = JSON.parse(line) as State;
                    
                    if (state.timestamp > maxTimestamp) {
                        maxTimestamp = state.timestamp;
                    }

                    if (state.timestamp <= meta.lastCheckPoint) {
                        break;
                    }
                    
                    if (bestState) continue;

                    if (currentPath) {
                        const rootPath = state.root.path;
                        if (rootPath === currentPath || rootPath.includes(currentPath) || currentPath.includes(rootPath)) {
                            bestState = state;
                        } else {
                            Logger.log(`Discard state: path mismatch. State root: ${rootPath}, Current: ${currentPath}`);
                        }
                    } else {
                        Logger.log(`Discard state: current project path is undefined. State root: ${state.root.path}`);
                        // continue;
                    }
                } catch (e) {
                    // ignore
                }
            }
            return [bestState, maxTimestamp];
        } catch (e) {
            Logger.error('Failed to read state file:', e);
        }
        return [null, 0];
    }

    public appendState(state: State) {
        if (this.isSyncing()) {
            return
        }
        
        Logger.log(`Appending state from ${state.source} with timestamp ${state.timestamp}`);
        Logger.log(`State content: ${JSON.stringify(state)}`);

        try {
            // Conflict Check
            const [currentState, _] = this.readStateFromFile();
            const meta = IdeMetaData.getInstance();
            if (currentState && currentState.timestamp > meta.lastCheckPoint) {
                Logger.log(`Conflict detected. Local: ${meta.lastCheckPoint}, Remote: ${currentState.timestamp}`);
                meta.lastCheckPoint = currentState.timestamp;
                this.onStateChanged(currentState);
                return;
            }

            const replacer = (key: string, value: any) => {
                if (value === null) return undefined;
                if (value === false) return undefined;
                // Don't strip empty arrays, they are needed for structure
                return value;
            };

            const line = JSON.stringify(state, replacer) + '\n';
            
            // Check file size for truncation (1MB limit)
            let shouldTruncate = false;
            try {
                if (fs.existsSync(this.stateFile)) {
                    const stats = fs.statSync(this.stateFile);
                    if (stats.size > 1024 * 1024) {
                        shouldTruncate = true;
                    }
                }
            } catch (e) {
                // Ignore stat error
            }

            if (shouldTruncate) {
                fs.writeFileSync(this.stateFile, line);
                this.lastFileSize = line.length;
            } else {
                fs.appendFileSync(this.stateFile, line);
                this.lastFileSize += line.length;
            }
            meta.lastCheckPoint = state.timestamp;
        } catch (e) {
            Logger.error('Failed to append state:', e);
        }
    }

    private watchStateFile() {
        Logger.log(`Watching state file: ${this.stateFile}`);
        const dir = path.dirname(this.stateFile);
        
        let fsWait: NodeJS.Timeout | null = null;
        try {
            this.watcher = fs.watch(dir, (eventType, filename) => {
                if (filename === 'state.json') {
                    if (fsWait) return;
                    fsWait = setTimeout(() => {
                        fsWait = null;
                        this.readLatestState();
                    }, 100);
                }
            });
        } catch (e) {
            Logger.error('Failed to watch directory:', e);
        }
    }

    private readLatestState() {
        if (!fs.existsSync(this.stateFile)) return;

        try {
            const [state, maxTimestamp] = this.readStateFromFile();
            const meta = IdeMetaData.getInstance();
            
            if (maxTimestamp > meta.lastCheckPoint) {
                meta.lastCheckPoint = maxTimestamp;
            }

            if (state) {
                this.onStateChanged(state);
            }
        } catch (e) {
            Logger.error('Failed to read state file:', e);
        }
    }
}
