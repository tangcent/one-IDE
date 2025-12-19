import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as vscode from 'vscode';
import { minimatch } from 'minimatch';
const ignore = require('ignore');
import { Config } from '../types';
import { Logger } from '../logger';

export class ConfigService {
    private config: Config = { excludeFiles: [], excludeGitIgnore: false };
    private gitIgnoreCache: Map<string, any> = new Map();
    private configFile: string;
    private oneIdeDir: string;
    private disposables: vscode.Disposable[] = [];

    constructor(oneIdeDir?: string) {
        this.oneIdeDir = oneIdeDir || path.join(os.homedir(), '.one-ide');
        this.configFile = path.join(this.oneIdeDir, 'config.json');
        this.init();
    }
    
    public dispose() {
        this.disposables.forEach(d => d.dispose());
        this.disposables = [];
    }

    private init() {
        this.ensureOneIdeDir();
        this.loadConfig();
        
        const configListener = vscode.workspace.onDidChangeConfiguration(event => {
            if (event.affectsConfiguration('oneIde')) {
                Logger.log('Configuration changed, reloading...');
                this.loadConfig();
                this.saveConfigToDisk();
            }
        });
        this.disposables.push(configListener);
    }

    private ensureOneIdeDir() {
        if (!fs.existsSync(this.oneIdeDir)) {
            try {
                fs.mkdirSync(this.oneIdeDir);
            } catch (e) {
                Logger.error('Failed to create one-ide dir:', e);
            }
        }
    }

    private loadConfig() {
        try {
            const config = vscode.workspace.getConfiguration('oneIde');
            this.config = {
                excludeFiles: config.get<string[]>('excludeFiles', []),
                excludeGitIgnore: config.get<boolean>('excludeGitIgnore', false)
            };
            Logger.log('Loaded config:', this.config);
        } catch (e) {
            Logger.error('Failed to load config:', e);
        }
    }

    private saveConfigToDisk() {
        Logger.log(`Attempting to save config to: ${this.configFile}`);
        try {
            this.ensureOneIdeDir();
            fs.writeFileSync(this.configFile, JSON.stringify(this.config, null, 2));
            Logger.log('Saved config to disk:', this.configFile);
        } catch (e) {
            Logger.error('Failed to save config to disk:', e);
        }
    }

    public shouldSyncFile(filePath: string): boolean {
        // 1. Check excludeFiles (glob patterns)
        const fileName = path.basename(filePath);
        for (const pattern of this.config.excludeFiles) {
            if (minimatch(fileName, pattern)) {
                return false;
            }
        }

        // 2. Check excludeGitIgnore
        if (this.config.excludeGitIgnore) {
            if (this.isIgnoredByGit(filePath)) {
                return false;
            }
        }

        return true;
    }

    private isIgnoredByGit(filePath: string): boolean {
        const workspaceFolder = vscode.workspace.getWorkspaceFolder(vscode.Uri.file(filePath));
        if (!workspaceFolder) return false;

        const rootPath = workspaceFolder.uri.fsPath;
        const gitIgnorePath = path.join(rootPath, '.gitignore');

        if (!fs.existsSync(gitIgnorePath)) return false;

        try {
            // Check cache
            let ig = this.gitIgnoreCache.get(rootPath);
            // Simple cache strategy: re-read if we don't have it. 
            // In a real world scenario we might want to watch .gitignore files too.
            
            if (!ig) {
                const gitIgnoreContent = fs.readFileSync(gitIgnorePath, 'utf-8');
                ig = ignore().add(gitIgnoreContent);
                this.gitIgnoreCache.set(rootPath, ig);
            }

            const relativePath = path.relative(rootPath, filePath);
            return ig.ignores(relativePath);

        } catch (e) {
            Logger.error('Error checking gitignore:', e);
            return false;
        }
    }
}
