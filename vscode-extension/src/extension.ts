import * as vscode from 'vscode';
import { SyncService } from './syncService';
import { RuleService } from './services/RuleService';
import { ConfigService } from './services/ConfigService';
import { AITool } from './services/AITool';
import { OneIdeCore } from './services/OneIdeCore';
import { Logger } from './logger';

let syncService: SyncService;
let ruleService: RuleService;
let configService: ConfigService;
let oneIdeCore: OneIdeCore;

/**
 * Activates the extension.
 * This function is called when the extension is activated (e.g., on startup or when a command is triggered).
 * It initializes the core services: ConfigService, SyncService, and RuleService.
 * 
 * @param context The extension context provided by VS Code.
 */
export function activate(context: vscode.ExtensionContext) {
    Logger.log('Activating extension...');

    try {
        configService = new ConfigService();
        context.subscriptions.push(configService);

        // Initialize AITool early to ensure it's available for all services
        AITool.initialize(context, configService);

        oneIdeCore = new OneIdeCore(configService);
        context.subscriptions.push(oneIdeCore);

        // Create SyncService with ClusterService, StateService, and IdeConnector
        syncService = new SyncService(
            oneIdeCore.getClusterService(),
            oneIdeCore.getStateService(),
            oneIdeCore.getIdeConnector()
        );
        syncService.start();

        ruleService = new RuleService(configService, oneIdeCore.getClusterService());
        ruleService.start();

        // Register Toggle Command
        let toggleCommand = vscode.commands.registerCommand('one-ide.toggle', () => {
            syncService.toggleSync();
        });

        // Register Show Log Command
        let showLogCommand = vscode.commands.registerCommand('one-ide.showLog', () => {
            Logger.show();
        });

        context.subscriptions.push(syncService);
        // RuleService doesn't implement Disposable via extension context simply, 
        // but we can wrap it or just rely on deactivate.
        // Actually it has dispose() method, so we can wrap it in a Disposable.
        context.subscriptions.push({ dispose: () => ruleService.dispose() });
        
        context.subscriptions.push(toggleCommand);
        context.subscriptions.push(showLogCommand);
        Logger.log('Extension activated successfully.');
    } catch (error) {
        Logger.error('Activation failed:', error);
        vscode.window.showErrorMessage(`One-IDE: Activation failed. ${error}`);
    }
}

export function deactivate() {
    if (syncService) {
        syncService.dispose();
    }
    if (ruleService) {
        ruleService.dispose();
    }
    if (oneIdeCore) {
        oneIdeCore.dispose();
    }
    if (configService) {
        configService.dispose();
    }
}
