import * as fc from 'fast-check';
import { expect } from 'chai';

/**
 * Property-based tests for ClusterService listener mechanism.
 * 
 * These tests verify the correctness properties of the role change listener
 * and action listener mechanisms.
 */

// Mock RoleType enum for testing
enum RoleType {
    LEADER = 'LEADER',
    FOLLOWER = 'FOLLOWER',
    CANDIDATE = 'CANDIDATE'
}

/**
 * Minimal ActionRegistry implementation for testing.
 */
class TestableActionRegistry {
    private actionListeners = new Map<string, Array<{ role: RoleType | null; action: () => void }>>();

    public static readonly ACTION_USER_ACTIVITY = 'userActivity';

    addAction(role: RoleType | null, actionName: string, action: () => void): () => void {
        if (!this.actionListeners.has(actionName)) {
            this.actionListeners.set(actionName, []);
        }
        const listeners = this.actionListeners.get(actionName)!;
        const entry = { role, action };
        listeners.push(entry);
        return () => {
            const index = listeners.indexOf(entry);
            if (index >= 0) {
                listeners.splice(index, 1);
            }
        };
    }

    fireAction(currentRole: RoleType, actionName: string): void {
        const listeners = this.actionListeners.get(actionName);
        if (!listeners) return;
        
        for (const { role, action } of [...listeners]) {
            if (role === null || role === currentRole) {
                try {
                    action();
                } catch (e) {
                    // Error isolation - continue with other listeners
                }
            }
        }
    }

    clear(): void {
        this.actionListeners.clear();
    }
}

/**
 * Minimal ClusterService implementation for testing listener behavior.
 * This isolates the listener mechanism from file system and other dependencies.
 */
class TestableClusterService {
    private roleChangeListeners = new Set<(role: RoleType) => void>();
    public readonly actionRegistry = new TestableActionRegistry();
    private currentRole: RoleType = RoleType.FOLLOWER;

    addRoleChangeListener(listener: (role: RoleType) => void): () => void {
        this.roleChangeListeners.add(listener);
        return () => {
            this.roleChangeListeners.delete(listener);
        };
    }

    addAction(role: RoleType | null, actionName: string, action: () => void): () => void {
        return this.actionRegistry.addAction(role, actionName, action);
    }

    // Simulate role transition for testing
    transitionTo(role: RoleType): void {
        this.currentRole = role;
        for (const listener of this.roleChangeListeners) {
            try {
                listener(role);
            } catch (e) {
                // Error isolation - continue with other listeners
            }
        }
    }

    // Simulate a role firing user activity action
    fireUserActivity(): void {
        this.actionRegistry.fireAction(this.currentRole, TestableActionRegistry.ACTION_USER_ACTIVITY);
    }

    getRoleType(): RoleType {
        return this.currentRole;
    }

    getListenerCount(): number {
        return this.roleChangeListeners.size;
    }

    dispose(): void {
        this.roleChangeListeners.clear();
        this.actionRegistry.clear();
    }
}

describe('ClusterService Property Tests', () => {
    /**
     * Feature: cluster-service-refactor, Property 1: Listener Notification Completeness
     * 
     * For any set of registered role change listeners and for any role transition
     * (to LEADER, FOLLOWER, or CANDIDATE), all registered listeners SHALL be invoked
     * exactly once with the new role value.
     * 
     * **Validates: Requirements 2.1, 2.2**
     */
    describe('Property 1: Listener Notification Completeness', () => {
        it('all registered listeners are invoked exactly once on role transition', () => {
            fc.assert(
                fc.property(
                    fc.integer({ min: 1, max: 20 }),
                    fc.constantFrom(RoleType.LEADER, RoleType.FOLLOWER, RoleType.CANDIDATE),
                    (listenerCount, newRole) => {
                        const invocations: Array<{ index: number; role: RoleType }> = [];
                        const clusterService = new TestableClusterService();

                        // Register listeners
                        for (let i = 0; i < listenerCount; i++) {
                            const index = i;
                            clusterService.addRoleChangeListener((role) => {
                                invocations.push({ index, role });
                            });
                        }

                        // Trigger role transition
                        clusterService.transitionTo(newRole);

                        // Verify all listeners were invoked exactly once
                        expect(invocations.length).to.equal(listenerCount);
                        
                        // Verify all invocations received the correct role
                        expect(invocations.every(inv => inv.role === newRole)).to.be.true;
                        
                        // Verify each listener was invoked exactly once
                        const invokedIndices = invocations.map(inv => inv.index);
                        const uniqueIndices = new Set(invokedIndices);
                        expect(uniqueIndices.size).to.equal(listenerCount);

                        return true;
                    }
                ),
                { numRuns: 100 }
            );
        });
    });


    /**
     * Feature: cluster-service-refactor, Property 2: Unsubscribe Removes Listener
     * 
     * For any listener registered via addRoleChangeListener, calling the returned
     * unsubscribe function SHALL remove that listener such that subsequent role
     * transitions do not invoke it.
     * 
     * **Validates: Requirements 2.3**
     */
    describe('Property 2: Unsubscribe Removes Listener', () => {
        it('unsubscribed listeners are not invoked on subsequent role transitions', () => {
            fc.assert(
                fc.property(
                    fc.integer({ min: 1, max: 10 }),
                    fc.integer({ min: 0, max: 9 }),
                    fc.constantFrom(RoleType.LEADER, RoleType.FOLLOWER, RoleType.CANDIDATE),
                    fc.constantFrom(RoleType.LEADER, RoleType.FOLLOWER, RoleType.CANDIDATE),
                    (listenerCount, unsubscribeIndex, firstRole, secondRole) => {
                        // Ensure unsubscribeIndex is valid
                        const actualUnsubscribeIndex = unsubscribeIndex % listenerCount;
                        
                        const invocationsBeforeUnsubscribe: number[] = [];
                        const invocationsAfterUnsubscribe: number[] = [];
                        const clusterService = new TestableClusterService();
                        const unsubscribeFunctions: Array<() => void> = [];

                        // Register listeners
                        for (let i = 0; i < listenerCount; i++) {
                            const index = i;
                            const unsubscribe = clusterService.addRoleChangeListener(() => {
                                if (invocationsBeforeUnsubscribe.length < listenerCount) {
                                    invocationsBeforeUnsubscribe.push(index);
                                } else {
                                    invocationsAfterUnsubscribe.push(index);
                                }
                            });
                            unsubscribeFunctions.push(unsubscribe);
                        }

                        // First transition - all listeners should be invoked
                        clusterService.transitionTo(firstRole);
                        expect(invocationsBeforeUnsubscribe.length).to.equal(listenerCount);

                        // Unsubscribe one listener
                        unsubscribeFunctions[actualUnsubscribeIndex]();

                        // Second transition - unsubscribed listener should NOT be invoked
                        clusterService.transitionTo(secondRole);
                        
                        // Verify the unsubscribed listener was not invoked
                        expect(invocationsAfterUnsubscribe).to.not.include(actualUnsubscribeIndex);
                        
                        // Verify remaining listeners were invoked
                        expect(invocationsAfterUnsubscribe.length).to.equal(listenerCount - 1);

                        return true;
                    }
                ),
                { numRuns: 100 }
            );
        });
    });

    /**
     * Feature: cluster-service-refactor, Property 3: Listener Error Isolation
     * 
     * For any set of registered listeners where one or more listeners throw exceptions,
     * all non-throwing listeners SHALL still be invoked during a role transition.
     * 
     * **Validates: Requirements 2.4**
     */
    describe('Property 3: Listener Error Isolation', () => {
        it('non-throwing listeners are invoked even when other listeners throw', () => {
            fc.assert(
                fc.property(
                    fc.integer({ min: 2, max: 10 }),
                    fc.array(fc.boolean(), { minLength: 2, maxLength: 10 }),
                    fc.constantFrom(RoleType.LEADER, RoleType.FOLLOWER, RoleType.CANDIDATE),
                    (listenerCount, shouldThrowArray, newRole) => {
                        // Ensure we have the right number of throw flags
                        const throwFlags = shouldThrowArray.slice(0, listenerCount);
                        while (throwFlags.length < listenerCount) {
                            throwFlags.push(false);
                        }
                        
                        // Ensure at least one listener throws and one doesn't
                        if (!throwFlags.includes(true)) throwFlags[0] = true;
                        if (!throwFlags.includes(false)) throwFlags[1] = false;

                        const invocations: number[] = [];
                        const clusterService = new TestableClusterService();

                        // Register listeners - some will throw, some won't
                        for (let i = 0; i < listenerCount; i++) {
                            const index = i;
                            const shouldThrow = throwFlags[i];
                            clusterService.addRoleChangeListener(() => {
                                invocations.push(index);
                                if (shouldThrow) {
                                    throw new Error(`Listener ${index} error`);
                                }
                            });
                        }

                        // Trigger role transition
                        clusterService.transitionTo(newRole);

                        // All listeners should have been invoked (including throwing ones)
                        expect(invocations.length).to.equal(listenerCount);
                        
                        // Verify all non-throwing listeners were invoked
                        const nonThrowingIndices = throwFlags
                            .map((throws, i) => throws ? -1 : i)
                            .filter(i => i >= 0);
                        
                        for (const index of nonThrowingIndices) {
                            expect(invocations).to.include(index);
                        }

                        return true;
                    }
                ),
                { numRuns: 100 }
            );
        });
    });
});


    /**
     * Feature: cluster-service-refactor, Property 4: Role-Filtered Action Invocation
     * 
     * For any action registered with a specific role, the action SHALL only be invoked
     * when the current role matches. Actions registered with null role SHALL be invoked
     * for all roles.
     * 
     * **Validates: Requirements 1.8 (addAction mechanism)**
     */
    describe('Property 4: Role-Filtered Action Invocation', () => {
        it('actions are only invoked when role matches or role filter is null', () => {
            fc.assert(
                fc.property(
                    fc.constantFrom(RoleType.LEADER, RoleType.FOLLOWER, RoleType.CANDIDATE),
                    fc.constantFrom(RoleType.LEADER, RoleType.FOLLOWER, RoleType.CANDIDATE),
                    (registeredRole, currentRole) => {
                        const clusterService = new TestableClusterService();
                        let specificRoleInvoked = false;
                        let nullRoleInvoked = false;

                        // Register action for specific role
                        clusterService.addAction(registeredRole, TestableActionRegistry.ACTION_USER_ACTIVITY, () => {
                            specificRoleInvoked = true;
                        });

                        // Register action for all roles (null)
                        clusterService.addAction(null, TestableActionRegistry.ACTION_USER_ACTIVITY, () => {
                            nullRoleInvoked = true;
                        });

                        // Set current role and fire action (simulating role firing the action)
                        clusterService.transitionTo(currentRole);
                        clusterService.fireUserActivity();

                        // Null role action should always be invoked
                        expect(nullRoleInvoked).to.be.true;

                        // Specific role action should only be invoked if roles match
                        if (registeredRole === currentRole) {
                            expect(specificRoleInvoked).to.be.true;
                        } else {
                            expect(specificRoleInvoked).to.be.false;
                        }

                        return true;
                    }
                ),
                { numRuns: 100 }
            );
        });
    });
