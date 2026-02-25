import { ClusterService } from '../../ClusterService';
import { ActionRegistry, RoleType } from '../ActionRegistry';

export interface IRole {
    init(): Promise<void>;
    dispose(): void;
    onUserActivity(): Promise<void>;
    onHeartbeat(): Promise<void>;
}

export abstract class BaseRole implements IRole {
    constructor(protected cluster: ClusterService) {}
    
    protected get actionRegistry(): ActionRegistry {
        return this.cluster.actionRegistry;
    }
    
    protected abstract get role(): RoleType;

    abstract init(): Promise<void>;
    
    dispose(): void {
        // Default cleanup
    }

    async onUserActivity(): Promise<void> {
        // Fire user activity action for this role
        this.actionRegistry.fireAction(this.role, ActionRegistry.ACTION_USER_ACTIVITY);
    }

    async onHeartbeat(): Promise<void> {
        // Fire heartbeat action for this role
        this.actionRegistry.fireAction(this.role, ActionRegistry.ACTION_HEARTBEAT);
    }
}
