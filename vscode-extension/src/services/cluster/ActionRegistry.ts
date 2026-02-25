import { Logger } from '../../logger';

/**
 * Role types for the cluster.
 */
export enum RoleType {
    LEADER = 'LEADER',
    FOLLOWER = 'FOLLOWER',
    CANDIDATE = 'CANDIDATE'
}

/**
 * Registry for role-based actions.
 * 
 * Business services register actions with specific roles and action names.
 * Roles fire actions through this registry, and only matching listeners are invoked.
 */
export class ActionRegistry {
    /**
     * Action listeners map: actionName -> list of { role filter, action callback }
     * When role is null, the action is triggered for all roles
     */
    private actionListeners = new Map<string, Array<{ role: RoleType | null; action: () => void }>>();

    // Standard action names
    public static readonly ACTION_USER_ACTIVITY = 'userActivity';
    public static readonly ACTION_HEARTBEAT = 'heartbeat';
    public static readonly ACTION_INIT = 'init';

    /**
     * Register an action listener for a specific role and action name.
     * 
     * @param role The role to listen for, or null to listen for all roles
     * @param actionName The name of the action to listen for
     * @param action The callback to invoke when the action occurs
     * @returns An unsubscribe function to remove the listener
     */
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

    /**
     * Fire an action, notifying all registered listeners that match the current role.
     * Called by Role implementations.
     * 
     * @param currentRole The current role firing the action
     * @param actionName The name of the action being fired
     */
    fireAction(currentRole: RoleType, actionName: string): void {
        const listeners = this.actionListeners.get(actionName);
        if (!listeners) return;

        for (const { role, action } of [...listeners]) {
            // Invoke if role is null (all roles) or matches current role
            if (role === null || role === currentRole) {
                try {
                    action();
                } catch (e) {
                    Logger.error(`Action listener error for '${actionName}' in role ${currentRole}`, e);
                }
            }
        }
    }

    /**
     * Clear all registered action listeners.
     */
    clear(): void {
        this.actionListeners.clear();
    }
}
