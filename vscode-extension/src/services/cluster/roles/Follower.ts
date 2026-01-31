import { BaseRole } from './BaseRole';
import { Logger } from '../../../logger';
import { ActionRegistry, RoleType } from '../ActionRegistry';

export class Follower extends BaseRole {
    private lastLeaderId: string | null = null;
    
    protected get role(): RoleType {
        return RoleType.FOLLOWER;
    }

    async init(): Promise<void> {
        Logger.log('Role: Follower initialized');
        this.actionRegistry.fireAction(this.role, ActionRegistry.ACTION_INIT);
    }

    override dispose(): void {
        super.dispose();
    }

    async onUserActivity(): Promise<void> {
        // Fire action first
        await super.onUserActivity();
        
        // Then handle role-specific logic
        if (!this.cluster.checkLeaderHealth()) {
            Logger.log('Follower: User activity and leader unhealthy -> Switching to Candidate');
            await this.cluster.becomeCandidate();
        }
    }

    async onHeartbeat(): Promise<void> {
        await super.onHeartbeat();
        const leaderInfo = this.cluster.getLeaderInfo();
        if (leaderInfo && leaderInfo.id !== this.lastLeaderId) {
            this.cluster.getIdeConnector().checkPluginVersion(leaderInfo);
            this.lastLeaderId = leaderInfo.id;
        }
    }
}
