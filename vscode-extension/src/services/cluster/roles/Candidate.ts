import { BaseRole } from './BaseRole';
import { Logger } from '../../../logger';

export class Candidate extends BaseRole {
    async init(): Promise<void> {
        Logger.log('Role: Candidate initialized');
        await this.tryElection();
    }

    override dispose(): void {
        super.dispose();
    }

    async onUserActivity(): Promise<void> {
        // Already candidate, maybe retry election?
        await this.tryElection();
    }

    async onHeartbeat(): Promise<void> {
        await super.onHeartbeat();
        await this.tryElection();
    }

    private async tryElection() {
        if (!this.cluster.getIdeConnector().isWindowFocused()) {
            Logger.log('Window not focused, becoming Follower');
            await this.cluster.becomeFollower();
            return;
        }

        Logger.log('Candidate attempting election...');
        if (await this.cluster.tryAcquireLeadership()) {
            Logger.log('Election successful, becoming Leader');
            await this.cluster.becomeLeader();
        } else {
            Logger.log('Election failed, reverting to Follower');
            await this.cluster.becomeFollower();
        }
    }
}
