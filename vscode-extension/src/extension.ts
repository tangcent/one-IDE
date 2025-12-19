import * as vscode from 'vscode';
import { SyncService } from './syncService';
import { Logger } from './logger';

let syncService: SyncService;

export function activate(context: vscode.ExtensionContext) {
    Logger.log('Activating extension...');

    try {
        syncService = new SyncService();
        syncService.start();

        // Register Toggle Command
        let toggleCommand = vscode.commands.registerCommand('one-ide.toggle', () => {
            syncService.toggleSync();
        });

        // Register Show Log Command
        let showLogCommand = vscode.commands.registerCommand('one-ide.showLog', () => {
            Logger.show();
        });

        context.subscriptions.push(syncService);
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
}
