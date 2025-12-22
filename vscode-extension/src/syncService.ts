import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { IdeMetaData } from './IdeMetaData';
import { ConfigService } from './services/ConfigService';
import { StateService } from './services/StateService';
import { ClusterService } from './services/ClusterService';
import { IdeConnector } from './services/IdeConnector';
import { Logger } from './logger';

const ONE_IDE_DIR = path.join(os.homedir(), '.one-ide');

export class SyncService implements vscode.Disposable {
    private sourceId: string;
    private isEnabled: boolean = true;
    private statusBarItem: vscode.StatusBarItem;

    private configService!: ConfigService;
    private stateService!: StateService;
    private clusterService!: ClusterService;
    private ideConnector!: IdeConnector;

    constructor(configService: ConfigService) {
        const meta = IdeMetaData.getInstance();
        this.sourceId = meta.id;
        Logger.setMetaData(meta);
        Logger.log(`Source ID: ${this.sourceId}`);

        try {
            this.ensureOneIdeDir();
            this.configService = configService;
            
            // Initialize Services
            this.stateService = new StateService(ONE_IDE_DIR);
            this.ideConnector = new IdeConnector(this.configService);
            this.clusterService = new ClusterService(this.ideConnector, this.stateService);
            
        } catch (e) {
            Logger.error('Initialization error:', e);
            this.isEnabled = false;
        }

        this.statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
        this.statusBarItem.command = 'one-ide.toggle';
        this.updateStatusBar();
        this.statusBarItem.show();
    }

    private ensureOneIdeDir() {
        if (!fs.existsSync(ONE_IDE_DIR)) {
            fs.mkdirSync(ONE_IDE_DIR, { recursive: true });
        }
    }

    public toggleSync() {
        this.isEnabled = !this.isEnabled;
        this.updateStatusBar();
        vscode.window.showInformationMessage(`One-IDE Sync: ${this.isEnabled ? 'Enabled' : 'Disabled'}`);
        // TODO: Actually pause/resume cluster service or connector?
        // For now, IdeConnector could check this flag, or we can just ignore it for the sake of simplicity 
        // as the requirement is to refactor architecture. 
        // But if we want to support toggle, we should probably propagate it.
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
        this.statusBarItem.dispose();
        if (this.clusterService) this.clusterService.dispose();
        if (this.stateService) this.stateService.dispose();
        if (this.ideConnector) this.ideConnector.dispose();
    }
}
