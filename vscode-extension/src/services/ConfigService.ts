import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as vscode from 'vscode';
import { minimatch } from 'minimatch';
const ignore = require('ignore');
import { Config } from '../types';
import { Logger } from '../logger';

export class ConfigService implements vscode.Disposable {
    private config: Config = { 
        excludeFiles: [], 
        excludeGitIgnore: false,
        syncRules: true,
        currentTool: 'Auto'
    };
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
        
        // Watch for VS Code configuration changes (Project Settings)
        const configListener = vscode.workspace.onDidChangeConfiguration(event => {
            if (event.affectsConfiguration('oneIde')) {
                Logger.log('VS Code Configuration changed, reloading...');
                this.loadConfig();
            }
        });
        this.disposables.push(configListener);

        // Watch for config.json changes (Global Settings)
        try {
            fs.watchFile(this.configFile, (curr, prev) => {
                if (curr.mtime !== prev.mtime) {
                    Logger.log('Config file changed, reloading...');
                    this.loadConfig();
                }
            });
            this.disposables.push({ dispose: () => fs.unwatchFile(this.configFile) });
        } catch (e) {
            Logger.error('Failed to watch config file:', e);
        }
    }

    public getConfig(): Config {
        return this.config;
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
            // 1. Load Global Settings from config.json
            let globalConfig: Partial<Config> = {};
            if (fs.existsSync(this.configFile)) {
                try {
                    const content = fs.readFileSync(this.configFile, 'utf-8');
                    const json = JSON.parse(content);
                    globalConfig = {
                        excludeFiles: json.excludeFiles || [],
                        excludeGitIgnore: json.excludeGitIgnore || false
                    };
                } catch (e) {
                    Logger.error('Failed to parse config.json', e);
                }
            }

            // 2. Load Project Settings from VS Code
            const vscodeConfig = vscode.workspace.getConfiguration('oneIde');
            
            this.config = {
                excludeFiles: globalConfig.excludeFiles || [],
                excludeGitIgnore: globalConfig.excludeGitIgnore || false,
                syncRules: vscodeConfig.get<boolean>('ai.syncRules', true),
                currentTool: vscodeConfig.get<string>('ai.currentTool', 'Auto')
            };

            Logger.log('Loaded config:', this.config);
        } catch (e) {
            Logger.error('Failed to load config:', e);
        }
    }

    public updateConfig(newConfig: Config) {
        this.config = newConfig;

        // 1. Save Global Settings to config.json
        this.saveGlobalConfig();

        // 2. Save Project Settings to VS Code
        const config = vscode.workspace.getConfiguration('oneIde');
        config.update('ai.syncRules', newConfig.syncRules, vscode.ConfigurationTarget.Workspace);
        config.update('ai.currentTool', newConfig.currentTool, vscode.ConfigurationTarget.Workspace);
    }

    private saveGlobalConfig() {
        Logger.log(`Attempting to save global config to: ${this.configFile}`);
        try {
            this.ensureOneIdeDir();
            const globalContent = {
                excludeFiles: this.config.excludeFiles,
                excludeGitIgnore: this.config.excludeGitIgnore
            };
            fs.writeFileSync(this.configFile, JSON.stringify(globalContent, null, 2));
            Logger.log('Saved global config to disk:', this.configFile);
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
