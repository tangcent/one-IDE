import { BaseRole } from './BaseRole';
import { Logger } from '../../../logger';
import { ActionRegistry, RoleType } from '../ActionRegistry';

export class Leader extends BaseRole {
    protected get role(): RoleType {
        return RoleType.LEADER;
    }

    async init(): Promise<void> {
        Logger.log('Role: Leader initialized');
        this.cluster.updateLeaderHeartbeat();
        this.actionRegistry.fireAction(this.role, ActionRegistry.ACTION_INIT);
    }

    async onUserActivity(): Promise<void> {
        this.cluster.updateLeaderHeartbeat();
        // Fire action - SyncService listens for this to publish state
        await super.onUserActivity();
    }

    async onHeartbeat(): Promise<void> {
        await super.onHeartbeat();
        if (!this.cluster.getIdeConnector().isWindowFocused()) {
            Logger.log('Leader: Window lost focus, downgrading to Follower');
            await this.cluster.becomeFollower();
            return;
        }
        if (!this.cluster.checkIfLeaderIsMe()) {
            Logger.log('Leader: Found another leader or leader file missing, downgrading to Follower');
            await this.cluster.becomeFollower();
        }
    }
}
