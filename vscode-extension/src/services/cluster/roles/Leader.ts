import { BaseRole } from './BaseRole';
import { Logger } from '../../../logger';

export class Leader extends BaseRole {
    private lastStateJson: string | null = null;

    async init(): Promise<void> {
        Logger.log('Role: Leader initialized');
        this.cluster.updateLeaderHeartbeat();
        
        // Immediately capture and publish state as we just became leader (likely due to user activity)
        await this.publishCurrentState();
    }

    async onUserActivity(): Promise<void> {
        this.cluster.updateLeaderHeartbeat();
        await this.publishCurrentState();
    }

    async onHeartbeat(): Promise<void> {
        await super.onHeartbeat();
        // Check if I am still the leader
        if (!this.cluster.checkIfLeaderIsMe()) {
            Logger.log('Leader: Found another leader or leader file missing, downgrading to Follower');
            await this.cluster.becomeFollower();
        }
    }

    private async publishCurrentState() {
        const connector = this.cluster.getIdeConnector();
        const state = await connector.captureState();
        const stateJson = JSON.stringify(state);

        if (stateJson !== this.lastStateJson) {
            Logger.log('State changed, publishing new state');
            await this.cluster.getStateService().publishState(state, this.cluster.getNodeId());
            this.lastStateJson = stateJson;
        } else {
            // Logger.log('State unchanged, skipping publish');
        }
    }
}
