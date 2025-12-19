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

    constructor(
        oneIdeDir: string, 
        onStateChanged: (state: State) => void,
        private getCurrentProjectPath: () => string | undefined
    ) {
        this.stateFile = path.join(oneIdeDir, 'state.json');
        this.onStateChanged = onStateChanged;
        this.init();
    }

    private init() {
        if (!fs.existsSync(this.stateFile)) {
            // Optional: Create if not exists, or just wait for write
        } else {
            this.lastFileSize = fs.statSync(this.stateFile).size;
            const state = this.readStateFromFile();
            if (state) {
                IdeMetaData.getInstance().lastKnownTimestamp = state.timestamp;
            }
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

    public getLastKnownTimestamp(): number {
        return IdeMetaData.getInstance().lastKnownTimestamp;
    }

    private readStateFromFile(): State | null {
        if (!fs.existsSync(this.stateFile)) return null;
        try {
            const content = fs.readFileSync(this.stateFile, 'utf-8');
            const lines = content.trim().split('\n');
            if (lines.length === 0) return null;

            const currentPath = this.getCurrentProjectPath();
            const meta = IdeMetaData.getInstance();

            for (let i = lines.length - 1; i >= 0; i--) {
                const line = lines[i].trim();
                if (!line) continue;

                try {
                    const state = JSON.parse(line) as State;
                    if (state.timestamp <= meta.lastKnownTimestamp) {
                        return null;
                    }
                    
                    if (currentPath) {
                        const rootPath = state.root.path;
                        if (rootPath === currentPath || rootPath.includes(currentPath) || currentPath.includes(rootPath)) {
                            return state;
                        }
                    } else {
                        // If we can't verify path, skip? Or return?
                        // Consistent with Kotlin logic: continue if path cannot be verified against current project.
                        continue;
                    }
                } catch (e) {
                    // ignore
                }
            }
        } catch (e) {
            Logger.error('Failed to read state file:', e);
        }
        return null;
    }

    public appendState(state: State) {
        if (this.isSyncing()) {
            return
        }
        
        Logger.log(`Appending state from ${state.source} with timestamp ${state.timestamp}`);
        Logger.log(`State content: ${JSON.stringify(state)}`);

        try {
            // Conflict Check
            const currentState = this.readStateFromFile();
            const meta = IdeMetaData.getInstance();
            if (currentState && currentState.timestamp > meta.lastKnownTimestamp) {
                Logger.log(`Conflict detected. Local: ${meta.lastKnownTimestamp}, Remote: ${currentState.timestamp}`);
                meta.lastKnownTimestamp = currentState.timestamp;
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
            meta.lastKnownTimestamp = state.timestamp;
        } catch (e) {
            Logger.error('Failed to append state:', e);
        }
    }

    private watchStateFile() {
        Logger.log(`Watching state file: ${this.stateFile}`);
        const dir = path.dirname(this.stateFile);
        
        let fsWait: NodeJS.Timeout | null = null;
        try {
            fs.watch(dir, (eventType, filename) => {
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
            const state = this.readStateFromFile();
            const meta = IdeMetaData.getInstance();
            if (state && state.timestamp > meta.lastKnownTimestamp) {
                meta.lastKnownTimestamp = state.timestamp;
                this.onStateChanged(state);
            }
        } catch (e) {
            Logger.error('Failed to read state file:', e);
        }
    }
}
