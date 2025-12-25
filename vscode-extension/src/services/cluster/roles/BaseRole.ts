import { ClusterService } from '../../ClusterService';

export interface IRole {
    init(): Promise<void>;
    dispose(): void;
    onUserActivity(): Promise<void>;
    onHeartbeat(): Promise<void>;
}

export abstract class BaseRole implements IRole {
    constructor(protected cluster: ClusterService) {}

    abstract init(): Promise<void>;
    
    dispose(): void {
        // Default cleanup
    }

    async onUserActivity(): Promise<void> {
        // Default: do nothing
    }

    async onHeartbeat(): Promise<void> {
        // Default: do nothing
    }
}
