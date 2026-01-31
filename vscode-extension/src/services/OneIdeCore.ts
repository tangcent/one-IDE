import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { ConfigService } from './ConfigService';
import { StateService } from './StateService';
import { IdeConnector } from './IdeConnector';
import { ClusterService } from './ClusterService';
import { Logger } from '../logger';

export class OneIdeCore implements vscode.Disposable {
    private oneIdeDir: string;
    private stateService: StateService;
    private ideConnector: IdeConnector;
    private clusterService: ClusterService;

    constructor(configService: ConfigService) {
        this.oneIdeDir = path.join(os.homedir(), '.one-ide');
        this.ensureOneIdeDir();

        this.ideConnector = new IdeConnector(configService);
        this.stateService = new StateService(this.oneIdeDir);
        this.clusterService = new ClusterService(this.ideConnector);
    }

    public getClusterService(): ClusterService {
        return this.clusterService;
    }

    public getStateService(): StateService {
        return this.stateService;
    }

    public getIdeConnector(): IdeConnector {
        return this.ideConnector;
    }

    private ensureOneIdeDir() {
        try {
            if (!fs.existsSync(this.oneIdeDir)) {
                fs.mkdirSync(this.oneIdeDir, { recursive: true });
            }
        } catch (e) {
            Logger.error('Failed to ensure ~/.one-ide directory', e);
        }
    }

    public dispose() {
        this.clusterService.dispose();
        this.stateService.dispose();
        this.ideConnector.dispose();
    }
}

