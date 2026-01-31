import * as vscode from 'vscode';
import { IdeMetaData } from './IdeMetaData';
import { ClusterService, RoleType } from './services/ClusterService';
import { StateService } from './services/StateService';
import { IdeConnector } from './services/IdeConnector';
import { State } from './types';
import { Logger } from './logger';
import { ActionRegistry } from './services/cluster/ActionRegistry';

/**
 * The core service that orchestrates One-IDE synchronization.
 *
 * Responsibilities:
 * - Subscribes to ClusterService role changes and user activity.
 * - Manages StateService watching based on role.
 * - Captures and publishes state when Leader.
 * - Applies received state when Follower.
 * - Manages the global enabled/disabled state for synchronization.
 * - Updates status bar widgets.
 */
export class SyncService implements vscode.Disposable {
    private sourceId: string;
    private isEnabled: boolean = true;
    private statusBarItem: vscode.StatusBarItem;

    private clusterService: ClusterService;
    private stateService: StateService;
    private ideConnector: IdeConnector;

    private unsubscribeRoleChange: (() => void) | null = null;
    private unsubscribeLeaderUserActivity: (() => void) | null = null;
    private lastStateJson: string | null = null;

    constructor(
        clusterService: ClusterService,
        stateService: StateService,
        ideConnector: IdeConnector
    ) {
        const meta = IdeMetaData.getInstance();
        this.sourceId = meta.id;
        Logger.setMetaData(meta);
        Logger.log(`Source ID: ${this.sourceId}`);
        
        this.clusterService = clusterService;
        this.stateService = stateService;
        this.ideConnector = ideConnector;

        this.statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
        this.statusBarItem.command = 'one-ide.toggle';
        this.updateStatusBar();
        this.statusBarItem.show();

        // Setup state received callback
        this.stateService.setOnStateReceived((state) => this.onStateReceived(state));

        // Subscribe to role changes
        this.unsubscribeRoleChange = this.clusterService.addRoleChangeListener((role) => {
            this.handleRoleChange(role);
        });

        // Subscribe to user activity only when LEADER role
        // The Leader role fires this action, SyncService listens
        this.unsubscribeLeaderUserActivity = this.clusterService.addAction(
            RoleType.LEADER,
            ActionRegistry.ACTION_USER_ACTIVITY,
            () => {
                if (this.isEnabled) {
                    this.publishCurrentState();
                }
            }
        );

        // Handle initial role
        this.handleRoleChange(this.clusterService.getRoleType());
    }

    private handleRoleChange(role: RoleType) {
        switch (role) {
            case RoleType.LEADER:
                this.stateService.stopWatching();
                // Publish current state immediately when becoming leader
                this.publishCurrentState();
                break;
            case RoleType.FOLLOWER:
                this.stateService.startWatching();
                break;
            case RoleType.CANDIDATE:
                // No action needed for candidate
                break;
        }
    }

    private async publishCurrentState() {
        const state = await this.ideConnector.captureState();
        const stateJson = JSON.stringify(state);

        if (stateJson !== this.lastStateJson) {
            Logger.log('State changed, publishing new state');
            await this.stateService.publishState(state, this.clusterService.getNodeId());
            this.lastStateJson = stateJson;
        }
    }

    private async onStateReceived(state: State) {
        if (this.clusterService.getRoleType() === RoleType.FOLLOWER && this.isEnabled) {
            await this.ideConnector.applyState(state);
        }
    }

    public toggleSync() {
        this.isEnabled = !this.isEnabled;
        this.updateStatusBar();
        vscode.window.showInformationMessage(`One-IDE Sync: ${this.isEnabled ? 'Enabled' : 'Disabled'}`);
    }

    private updateStatusBar() {
        if (this.isEnabled) {
            this.statusBarItem.text = '$(sync) One-IDE: On';
            this.statusBarItem.tooltip = 'Click to disable synchronization';
        } else {
            this.statusBarItem.text = '$(sync-ignored) One-IDE: Off';
            this.statusBarItem.tooltip = 'Click to enable synchronization';
        }
    }

    public async start() {
        Logger.log(`Starting SyncService. SourceID: ${this.sourceId}`);
        // ClusterService starts automatically in constructor/init
    }

    public dispose() {
        this.isEnabled = false;
        this.unsubscribeRoleChange?.();
        this.unsubscribeLeaderUserActivity?.();
        this.statusBarItem.dispose();
    }
}
