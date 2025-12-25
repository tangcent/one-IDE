import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { State, NodeInfo, CandidatesData } from '../types';
import { Logger } from '../logger';
import { IdeConnector } from './IdeConnector';
import { StateService } from './StateService';
import { IRole } from './cluster/roles/BaseRole';
import { Follower } from './cluster/roles/Follower';
import { Candidate } from './cluster/roles/Candidate';
import { Leader } from './cluster/roles/Leader';
import { IdeMetaData } from '../IdeMetaData';
import { ClusterConstants } from './cluster/ClusterConstants';

export enum RoleType {
    LEADER = 'LEADER',
    FOLLOWER = 'FOLLOWER',
    CANDIDATE = 'CANDIDATE'
}

/**
 * Manages the distributed role of this IDE instance within the One-IDE cluster.
 * 
 * Implements a Leader Election algorithm using a file-based lock mechanism.
 * The cluster coordinates via a shared directory (~/.one-ide/cluster).
 * 
 * Roles:
 * - LEADER: The instance responsible for capturing its state and publishing it to the cluster.
 *           Only the active IDE instance should be the Leader.
 * - FOLLOWER: Listens for state changes published by the Leader and applies them locally.
 * - CANDIDATE: A temporary state when a Follower detects an unhealthy Leader and attempts to become Leader.
 */
export class ClusterService {
    private oneIdeDir: string;
    private clusterDir: string;
    private nodeId: string;
    
    private currentRole: IRole | null = null
    private roleType: RoleType = RoleType.FOLLOWER;
    
    private heartbeatInterval: NodeJS.Timeout | null = null;
    
    // File paths
    private leaderFile: string;

    constructor(
        private ideConnector: IdeConnector,
        private stateService: StateService
    ) {
        this.oneIdeDir = path.join(os.homedir(), '.one-ide');
        this.clusterDir = path.join(this.oneIdeDir, 'cluster');
        
        if (!fs.existsSync(this.clusterDir)) {
            fs.mkdirSync(this.clusterDir, { recursive: true });
        }
        
        this.nodeId = IdeMetaData.getInstance().id;

        this.leaderFile = path.join(this.clusterDir, 'leader.json');

        this.init();
    }

    private async init() {
        // Setup dependencies
        this.ideConnector.setOnUserActivity(() => this.onUserActivity());
        this.stateService.setOnStateReceived((state) => this.onStateReceived(state));
        
        this.startHeartbeat();
        
        // Start as Follower
        await this.becomeFollower();
    }

    // --- Role Transitions ---

    public async becomeFollower() {
        if (this.roleType === RoleType.FOLLOWER && this.currentRole) return;
        
        await this.switchRole(new Follower(this), RoleType.FOLLOWER);
    }

    public async becomeCandidate() {
        if (this.roleType === RoleType.CANDIDATE && this.currentRole) return;
        
        await this.switchRole(new Candidate(this), RoleType.CANDIDATE);
    }

    public async becomeLeader() {
        if (this.roleType === RoleType.LEADER && this.currentRole) return;

        await this.switchRole(new Leader(this), RoleType.LEADER);
    }

    private async switchRole(newRole: IRole, type: RoleType) {
        if (this.currentRole) {
            this.currentRole.dispose();
        }
        Logger.log(`Switching role to ${type}`);
        this.roleType = type;
        this.currentRole = newRole;
        await this.currentRole.init();
    }

    // --- Events ---

    private async onUserActivity() {
        if (this.currentRole) {
            await this.currentRole.onUserActivity();
        }
    }

    private async onStateReceived(state: State) {
        if (this.roleType === RoleType.FOLLOWER) {
             await this.ideConnector.applyState(state);
        }
    }

    // --- Public API for Roles ---

    public getIdeConnector() {
        return this.ideConnector;
    }

    public getStateService() {
        return this.stateService;
    }

    public getNodeId() {
        return this.nodeId;
    }

    public updateLeaderHeartbeat() {
        const info: NodeInfo = {
            id: this.nodeId,
            timestamp: Date.now(),
            lastHeartbeat: Date.now()
        };
        fs.writeFileSync(this.leaderFile, JSON.stringify(info));
    }

    public checkLeaderHealth(): boolean {
        if (!fs.existsSync(this.leaderFile)) return false;
        try {
            const content = fs.readFileSync(this.leaderFile, 'utf-8');
            const info = JSON.parse(content) as NodeInfo;
            if (Date.now() - info.timestamp > ClusterConstants.LEADER_TIMEOUT) {
                return false;
            }
            return true;
        } catch (e) {
            return false;
        }
    }

    public checkIfLeaderIsMe(): boolean {
        if (!fs.existsSync(this.leaderFile)) return false;
        try {
            const content = fs.readFileSync(this.leaderFile, 'utf-8');
            const info = JSON.parse(content) as NodeInfo;
            return info.id === this.nodeId;
        } catch (e) {
            return false;
        }
    }

    public async tryAcquireLeadership(): Promise<boolean> {
        const lockDir = path.join(this.clusterDir, 'election.lock');
        if (this.acquireLock(lockDir)) {
            try {
                if (!this.checkLeaderHealth()) {
                    this.updateLeaderHeartbeat();
                    return true;
                }
            } finally {
                this.releaseLock(lockDir);
            }
        }
        return false;
    }

    // --- Internal ---

    private startHeartbeat() {
        this.heartbeatInterval = setInterval(async () => {
            if (this.currentRole) {
                await this.currentRole.onHeartbeat();
            }
        }, ClusterConstants.HEARTBEAT_INTERVAL);
    }

    private acquireLock(lockDir: string): boolean {
        try {
            fs.mkdirSync(lockDir);
            return true;
        } catch (e) {
            try {
                const stats = fs.statSync(lockDir);
                if (Date.now() - stats.mtimeMs > ClusterConstants.LOCK_STALE_TIMEOUT) {
                    try {
                        fs.rmdirSync(lockDir);
                        fs.mkdirSync(lockDir);
                        return true;
                    } catch (e2) {
                        return false;
                    }
                }
            } catch (statErr) {
            }
            return false;
        }
    }

    private releaseLock(lockDir: string) {
        try {
            fs.rmdirSync(lockDir);
        } catch (e) {
        }
    }

    public dispose() {
        if (this.heartbeatInterval) clearInterval(this.heartbeatInterval);
        if (this.currentRole) {
            this.currentRole.dispose();
        }
        
        // Cleanup my node directory
        try {
            // No node directory to cleanup
        } catch (e) {
            Logger.error('Failed to cleanup node directory', e);
        }
    }
}
