import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as vscode from 'vscode'; // This will resolve to our mock
import { ConfigService } from '../../services/ConfigService';

describe('ConfigService', () => {
    let tmpDir: string;
    let oneIdeDir: string;
    let service: ConfigService;
    let mockConfig: any;
    let configChangeCallback: (e: any) => void;

    beforeEach(() => {
        tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'one-ide-test-'));
        oneIdeDir = path.join(tmpDir, '.one-ide');
        
        mockConfig = {
            excludeFiles: [],
            excludeGitIgnore: false,
            'ai.syncRules': true,
            'ai.currentTool': 'Auto'
        };

        // Setup default mock behavior
        (vscode.workspace as any).getWorkspaceFolder = (uri: any) => {
             if (uri.fsPath.startsWith(tmpDir)) {
                 return { uri: { fsPath: tmpDir } };
             }
             return null;
        };

        (vscode.workspace as any).getConfiguration = (section: string) => {
            if (section === 'oneIde') {
                return {
                    get: (key: string, defaultValue: any) => {
                        // Handle both nested and direct keys for simplicity in mock
                        const fullKey = key.includes('.') ? key : `ai.${key}`;
                        if (mockConfig[key] !== undefined) return mockConfig[key];
                        if (mockConfig[fullKey] !== undefined) return mockConfig[fullKey];
                        return defaultValue;
                    },
                    update: (key: string, value: any, target: any) => {
                        mockConfig[key] = value;
                        return Promise.resolve();
                    }
                };
            }
            return { get: () => undefined, update: () => Promise.resolve() };
        };

        (vscode.workspace as any).onDidChangeConfiguration = (cb: any) => {
            configChangeCallback = cb;
            return { dispose: () => {} };
        };

        service = new ConfigService(oneIdeDir);
    });

    afterEach(() => {
        if (service) {
            service.dispose();
        }
        fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it('should load default config', () => {
        const config = (service as any).config; // access private
        assert.deepStrictEqual(config, { 
            excludeFiles: [], 
            excludeGitIgnore: false,
            syncRules: true,
            currentTool: 'Auto'
        });
    });

    it('should exclude files by pattern', () => {
        // Write global config
        const configFile = path.join(oneIdeDir, 'config.json');
        fs.mkdirSync(oneIdeDir, { recursive: true });
        fs.writeFileSync(configFile, JSON.stringify({
            excludeFiles: ['*.log', 'node_modules'],
            excludeGitIgnore: false
        }));
        
        // Trigger reload manually
        (service as any).loadConfig();

        assert.strictEqual(service.shouldSyncFile('/path/to/test.log'), false);
        assert.strictEqual(service.shouldSyncFile('/path/to/node_modules'), false);
        assert.strictEqual(service.shouldSyncFile('/path/to/src/main.ts'), true);
    });

    it('should exclude files by gitignore', () => {
        // Create .gitignore
        fs.writeFileSync(path.join(tmpDir, '.gitignore'), 'ignored.txt\nbuild/');
        
        // Write global config
        const configFile = path.join(oneIdeDir, 'config.json');
        fs.mkdirSync(oneIdeDir, { recursive: true });
        fs.writeFileSync(configFile, JSON.stringify({
            excludeFiles: [],
            excludeGitIgnore: true
        }));

        (service as any).loadConfig();

        // Check ignored file
        const ignoredFile = path.join(tmpDir, 'ignored.txt');
        assert.strictEqual(service.shouldSyncFile(ignoredFile), false);

        // Check ignored dir
        const ignoredDirFile = path.join(tmpDir, 'build/out.js');
        assert.strictEqual(service.shouldSyncFile(ignoredDirFile), false);

        // Check allowed file
        const allowedFile = path.join(tmpDir, 'src/main.ts');
        assert.strictEqual(service.shouldSyncFile(allowedFile), true);
    });

    it('should save config to disk when changed', () => {
        const newConfig = {
            excludeFiles: ['*.tmp'],
            excludeGitIgnore: false,
            syncRules: true,
            currentTool: 'Auto'
        };
        
        service.updateConfig(newConfig);

        // Check file existence
        const configFile = path.join(oneIdeDir, 'config.json');
        assert.ok(fs.existsSync(configFile), 'config.json should exist');
        
        // Check file content
        const content = JSON.parse(fs.readFileSync(configFile, 'utf-8'));
        assert.deepStrictEqual(content, {
            excludeFiles: ['*.tmp'],
            excludeGitIgnore: false
        });
        
        // Check project config updated (mock)
        assert.strictEqual(mockConfig['ai.syncRules'], true);
    });
});
