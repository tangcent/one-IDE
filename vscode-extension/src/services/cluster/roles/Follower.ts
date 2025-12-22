import { BaseRole } from './BaseRole';
import { Logger } from '../../../logger';

export class Follower extends BaseRole {
    async init(): Promise<void> {
        Logger.log('Role: Follower initialized');
        // Start watching for state changes
        this.cluster.getStateService().startWatching();
    }

    override dispose(): void {
        super.dispose();
        this.cluster.getStateService().stopWatching();
    }

    async onUserActivity(): Promise<void> {
        // User activity -> Check if leader is healthy
        if (!this.cluster.checkLeaderHealth()) {
            Logger.log('Follower: User activity and leader unhealthy -> Switching to Candidate');
            await this.cluster.becomeCandidate();
        }
    }

    async onHeartbeat(): Promise<void> {
        await super.onHeartbeat();
    }
}
