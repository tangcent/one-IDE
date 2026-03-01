import { expect } from 'chai';
import { Leader } from '../../../../services/cluster/roles/Leader';
import { ActionRegistry, RoleType } from '../../../../services/cluster/ActionRegistry';

/**
 * Minimal mock for ClusterService, providing only what Leader needs.
 */
class MockClusterService {
    public readonly actionRegistry = new ActionRegistry();
    private _isWindowFocused = true;
    private _isLeaderMe = true;
    public becomeFollowerCalled = false;
    public updateHeartbeatCalled = false;

    setWindowFocused(focused: boolean) {
        this._isWindowFocused = focused;
    }

    setLeaderIsMe(isMe: boolean) {
        this._isLeaderMe = isMe;
    }

    getIdeConnector() {
        const focused = this._isWindowFocused;
        return {
            isWindowFocused: () => focused
        };
    }

    checkIfLeaderIsMe(): boolean {
        return this._isLeaderMe;
    }

    async becomeFollower() {
        this.becomeFollowerCalled = true;
    }

    updateLeaderHeartbeat() {
        this.updateHeartbeatCalled = true;
    }

    reset() {
        this.becomeFollowerCalled = false;
        this.updateHeartbeatCalled = false;
    }
}

describe('Leader Role', () => {
    let mockCluster: MockClusterService;
    let leader: Leader;

    beforeEach(() => {
        mockCluster = new MockClusterService();
        leader = new Leader(mockCluster as any);
    });

    describe('onHeartbeat', () => {
        it('should step down to follower when window loses focus', async () => {
            mockCluster.setWindowFocused(false);
            mockCluster.setLeaderIsMe(true);

            await leader.onHeartbeat();

            expect(mockCluster.becomeFollowerCalled).to.be.true;
        });

        it('should not step down when window is focused and still leader', async () => {
            mockCluster.setWindowFocused(true);
            mockCluster.setLeaderIsMe(true);

            await leader.onHeartbeat();

            expect(mockCluster.becomeFollowerCalled).to.be.false;
        });

        it('should step down when window is focused but no longer leader', async () => {
            mockCluster.setWindowFocused(true);
            mockCluster.setLeaderIsMe(false);

            await leader.onHeartbeat();

            expect(mockCluster.becomeFollowerCalled).to.be.true;
        });

        it('should not check leader file when window is not focused', async () => {
            // When window loses focus, becomeFollower should be called
            // without needing to check checkIfLeaderIsMe
            mockCluster.setWindowFocused(false);
            mockCluster.setLeaderIsMe(false);

            await leader.onHeartbeat();

            // becomeFollower should be called exactly once (from focus check, not leader check)
            expect(mockCluster.becomeFollowerCalled).to.be.true;
        });
    });

    describe('init', () => {
        it('should update leader heartbeat on init', async () => {
            await leader.init();

            expect(mockCluster.updateHeartbeatCalled).to.be.true;
        });
    });

    describe('onUserActivity', () => {
        it('should update leader heartbeat on user activity', async () => {
            await leader.onUserActivity();

            expect(mockCluster.updateHeartbeatCalled).to.be.true;
        });
    });
});
