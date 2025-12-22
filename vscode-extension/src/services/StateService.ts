import * as fs from 'fs';
import * as path from 'path';
import { State, ClusterState } from '../types';
import { Logger } from '../logger';
import { IdeMetaData } from '../IdeMetaData';

/**
 * Handles the serialization, publication, and observation of IDE state.
 * 
 * The state is exchanged via a shared JSON file (~/.one-ide/cluster/state.json).
 * - The LEADER writes to this file when the user's context (open files, cursor) changes.
 * - FOLLOWERS watch this file for changes and apply the new state.
 */
export class StateService {
    private stateFile: string;
    private onStateReceivedCallback: ((state: State) => void) | null = null;
    private watcher: fs.FSWatcher | null = null;
    private lastStateTimestamp = 0;

    constructor(oneIdeDir: string) {
        this.stateFile = path.join(oneIdeDir, 'cluster', 'state.json');
    }

    public setOnStateReceived(callback: (state: State) => void) {
        this.onStateReceivedCallback = callback;
    }

    public async publishState(state: State, leaderId: string) {
        Logger.log(`Publishing state from ${state.source} with timestamp ${state.timestamp}`);

        const clusterState: ClusterState = {
            timestamp: Date.now(),
            leaderId: leaderId,
            state: state
        };

        try {
            const dir = path.dirname(this.stateFile);
            if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
            
            fs.writeFileSync(this.stateFile, JSON.stringify(clusterState, null, 2));
            IdeMetaData.getInstance().lastCheckPoint = state.timestamp;
        } catch (e) {
            Logger.error('Failed to publish state', e);
        }
    }

    public startWatching() {
        if (this.watcher) return;
        
        const dir = path.dirname(this.stateFile);
        if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });

        Logger.log(`Watching state file: ${this.stateFile}`);
        try {
            this.watcher = fs.watch(dir, (eventType, filename) => {
                if (filename === 'state.json') {
                    this.readLatestState();
                }
            });
            // Initial read
            this.readLatestState();
        } catch (e) {
            Logger.error('Failed to watch state file', e);
        }
    }

    public stopWatching() {
        if (this.watcher) {
            this.watcher.close();
            this.watcher = null;
        }
    }

    private readLatestState() {
        if (!fs.existsSync(this.stateFile)) return;

        try {
            const content = fs.readFileSync(this.stateFile, 'utf-8');
            const clusterState = JSON.parse(content) as ClusterState;
            const meta = IdeMetaData.getInstance();

            if (clusterState.timestamp > meta.lastCheckPoint) {
                meta.lastCheckPoint = clusterState.timestamp;
                if (this.onStateReceivedCallback) {
                    this.onStateReceivedCallback(clusterState.state);
                }
            }
        } catch (e) {
            Logger.log('Error reading state file: ' + e);
        }
    }

    public dispose() {
        this.stopWatching();
    }
}
