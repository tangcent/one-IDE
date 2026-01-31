import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { Logger } from '../logger';
import { Debouncer } from '../utils/Debouncer';
import { IdeMetaData } from '../IdeMetaData';
import { AITool, AIConfig } from './AITool';
import { ConfigService } from './ConfigService';
import { ClusterService, RoleType } from './ClusterService';
import { LocalStorage } from './LocalStorage';

interface RuleFile {
    path: string; // relative path from rule root
    content: string;
}

interface RuleStat {
    ai: string;
    file: string;
    mtime: number;
}

interface SyncState {
    source?: string;
    lastModified: number;
    isFromSynced: boolean;
    syncedTo: string[];
}

/**
 * Interface for building rule files for a target AI tool.
 */
interface RuleBuilder {
    buildRules(sourceFiles: Array<{ path: string, content: string }>, sourceAi: string): RuleFile[];
}

/**
 * Builds rules for tools that support folder-based rule structure.
 */
export class FolderRuleBuilder implements RuleBuilder {
    constructor(private ruleRoot: string, private extension?: string) { }

    buildRules(sourceFiles: Array<{ path: string, content: string }>, sourceAi: string): RuleFile[] {
        return sourceFiles.map(file => {
            const fileName = path.basename(file.path);
            let name = fileName;

            if (this.extension) {
                const ext = path.extname(name);
                if (ext && ext !== this.extension) {
                    name = name.substring(0, name.length - ext.length) + this.extension;
                } else if (!ext) {
                    name += this.extension;
                }
            }

            const rulePath = this.ruleRoot ? path.join(this.ruleRoot, name) : name;

            return {
                path: rulePath,
                content: file.content
            };
        });
    }
}

/**
 * Builds rules for tools that require a single file configuration.
 * Concatenates all source rules into one file.
 */
export class SingleFileRuleBuilder implements RuleBuilder {
    constructor(private targetPath: string) { }

    buildRules(sourceFiles: Array<{ path: string, content: string }>, sourceAi: string): RuleFile[] {
        // Iterate through each source file and extract its content
        const mergedContent = sourceFiles
            .map(file => file.content)
            .join('\n\n');

        // Return the single target file with the merged content
        return [{
            path: this.targetPath,
            content: mergedContent ? mergedContent + '\n\n' : ''
        }];
    }
}

/**
 * Service responsible for synchronizing AI rules between different tools.
 * It monitors file changes and triggers synchronization when rules are modified.
 */
export class RuleService {
    private debouncer = new Debouncer(1000); // 1 second debounce
    private currentAppName: string;
    private configService: ConfigService;
    private clusterService: ClusterService;
    private unsubscribeRoleChange: (() => void) | undefined;

    constructor(configService: ConfigService, clusterService: ClusterService) {
        this.configService = configService;
        this.clusterService = clusterService;
        this.currentAppName = IdeMetaData.getInstance().appName;
        Logger.log(`RuleService initialized. Detected App Name: ${this.currentAppName}`);
    }

    /**
     * Starts the RuleService.
     * Triggers synchronization only when this instance becomes the cluster Leader.
     */
    public start() {
        this.unsubscribeRoleChange = this.clusterService.addRoleChangeListener((role) => {
            if (role !== RoleType.LEADER) return;
            this.debouncer.debounce(() => this.checkAndSync());
        });
    }

    public dispose() {
        this.unsubscribeRoleChange?.();
        this.debouncer.cancel();
    }

    private listRuleFiles(rootPath: string, config: AIConfig): string[] {
        const ruleRootPath = path.join(rootPath, config.ruleRoot);
        if (!fs.existsSync(ruleRootPath)) return [];

        if (config.strategy === 'single-file') {
            const stat = fs.statSync(ruleRootPath);
            return stat.isFile() ? [ruleRootPath] : [];
        }

        const results: string[] = [];
        const extension = config.extension?.toLowerCase();
        const stack = [ruleRootPath];
        while (stack.length > 0) {
            const current = stack.pop() as string;
            const entries = fs.readdirSync(current, { withFileTypes: true });
            for (const entry of entries) {
                const entryPath = path.join(current, entry.name);
                if (entry.isDirectory()) {
                    stack.push(entryPath);
                } else if (entry.isFile()) {
                    if (!extension || entry.name.toLowerCase().endsWith(extension)) {
                        results.push(entryPath);
                    }
                }
            }
        }
        return results;
    }

    private readRuleStats(aiKey: string, filePaths: string[]): RuleStat[] {
        const rules: RuleStat[] = [];
        for (const filePath of filePaths) {
            try {
                const stat = fs.statSync(filePath);
                if (stat.isFile()) {
                    rules.push({ ai: aiKey, file: filePath, mtime: stat.mtimeMs });
                }
            } catch (e) {
                Logger.error(`Error processing file ${filePath}`, e);
            }
        }
        return rules;
    }

    private readRuleFiles(filePaths: string[]): RuleFile[] {
        const sources: RuleFile[] = [];
        for (const filePath of filePaths) {
            try {
                const stat = fs.statSync(filePath);
                if (stat.isFile()) {
                    sources.push({ path: filePath, content: fs.readFileSync(filePath, 'utf-8') });
                }
            } catch (e) {
                Logger.error(`Error processing file ${filePath}`, e);
            }
        }
        return sources;
    }

    /**
     * Retrieves the synchronization state for a specific rule root.
     * 
     * @param ruleRoot The root directory of the rules.
     * @param currentMtime The current modification time to validate against.
     * @returns The SyncState if found and valid, null otherwise.
     */
    private getSyncState(ruleRoot: string, currentMtime: number): SyncState | null {
        const state = LocalStorage.getInstance().getData<SyncState>(ruleRoot);
        if (state) {
            // Check if lastModified matches
            if (state.lastModified !== currentMtime) return null;
        }
        return state;
    }

    /**
     * Checks for rule updates and synchronizes them if necessary.
     * This is the main entry point for the synchronization logic.
     */
    private async checkAndSync() {
        if (!this.configService.getConfig().syncRules) {
            Logger.log('Sync disabled in config.');
            return;
        }

        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders) return;

        const rootPath = workspaceFolders[0].uri.fsPath;

        // 1. Collect all rule files and their mtimes
        const allRules: RuleStat[] = [];
        const aiTools = AITool.getInstance().getAllAIConfigs();

        for (const [aiKey, config] of Object.entries(aiTools)) {
            const filePaths = this.listRuleFiles(rootPath, config);
            allRules.push(...this.readRuleStats(aiKey, filePaths));
        }

        Logger.log(`Found ${allRules.length} rules.`);
        if (allRules.length === 0) return;

        const aiMaxMtimes = allRules.reduce<Record<string, number>>((acc, rule) => {
            acc[rule.ai] = Math.max(acc[rule.ai] ?? 0, rule.mtime);
            return acc;
        }, {});

        // 2. Filter candidates (ignore isFromSynced)
        const validCandidates = allRules.filter(rule => {
            const config = aiTools[rule.ai];
            const ruleRoot = path.join(rootPath, config.ruleRoot);
            const mtime = aiMaxMtimes[rule.ai];
            const state = this.getSyncState(ruleRoot, mtime);
            return !state || !state.isFromSynced;
        });

        Logger.log(`Valid candidates: ${validCandidates.length}`);
        if (validCandidates.length === 0) return;

        // 3. Find the latest modified rule from valid candidates
        const latest = validCandidates.reduce((prev, current) => (prev.mtime > current.mtime) ? prev : current);

        // 4. Identify current AI tool
        let currentToolKey = this.detectCurrentTool();
        Logger.log(`Detected current tool: ${currentToolKey}`);
        if (!currentToolKey) {
            Logger.log(`Current IDE '${this.currentAppName}' is not a known AI tool target. Skipping sync.`);
            return;
        }

        if (latest.ai === currentToolKey) {
            Logger.log(`Latest rule belongs to current tool (${currentToolKey}). No sync needed.`);
            return;
        }

        // Check locks
        if (this.isLocked(rootPath, latest.ai)) {
            Logger.log(`Sync ignored: Source ${latest.ai} is currently locked (active write).`);
            return;
        }

        const targetRuleRoot = path.join(rootPath, aiTools[currentToolKey].ruleRoot);
        const targetState = this.getSyncState(targetRuleRoot, aiMaxMtimes[currentToolKey] || 0);

        // Check if already synced
        if (targetState && targetState.source === latest.ai && targetState.lastModified >= latest.mtime) {
            Logger.log(`Already synced from ${latest.ai} to ${currentToolKey}.`);
            return;
        }

        // Check if source already believes it synced to us
        // This prevents re-syncing if the user manually reverts/modifies the target
        const sourceRuleRoot = path.join(rootPath, aiTools[latest.ai].ruleRoot);
        const sourceState = this.getSyncState(sourceRuleRoot, latest.mtime);

        if (sourceState && sourceState.syncedTo.includes(currentToolKey)) {
            Logger.log(`Source ${latest.ai} already synced to ${currentToolKey} (v${latest.mtime}). Skipping.`);
            return;
        }

        // 5. Sync
        Logger.log(`Latest rule modification: ${latest.ai} - ${latest.file}`);
        Logger.log(`Syncing rules from ${latest.ai} to ${currentToolKey}...`);

        if (this.tryAcquireLock(rootPath, currentToolKey)) {
            try {
                if (await this.syncRules(latest.ai, currentToolKey, rootPath)) {
                    this.updateSyncStates(rootPath, aiTools, latest, currentToolKey, targetState, aiMaxMtimes);
                }
            } finally {
                this.releaseLock(rootPath, currentToolKey);
            }
        } else {
            Logger.log(`Skipping sync: Could not acquire lock for ${currentToolKey} (recently synced).`);
        }
    }

    /**
     * Detects the currently active AI tool based on configuration or application name.
     * 
     * @returns The key of the detected AI tool, or undefined if not recognized.
     */
    private detectCurrentTool(): string | undefined {
        return AITool.getInstance().detectCurrentTool();
    }

    /**
     * Generates a unique lock file path for a given root path and AI tool.
     * 
     * @param rootPath The project root path.
     * @param aiTool The AI tool identifier.
     * @returns The absolute path to the lock file.
     */
    private getLockPath(rootPath: string, aiTool: string): string {
        const encodedPath = Buffer.from(rootPath).toString('base64').replace(/\//g, '_').replace(/\+/g, '-');
        const homeDir = os.homedir();
        const lockDir = path.join(homeDir, '.one-ide', 'locks');
        if (!fs.existsSync(lockDir)) {
            fs.mkdirSync(lockDir, { recursive: true });
        }
        return path.join(lockDir, `${encodedPath}-${aiTool}.lock`);
    }

    /**
     * Checks if a lock exists and is valid (not stale) for the given AI tool.
     * A lock is considered stale if it's older than 3 minutes.
     * 
     * @param rootPath The project root path.
     * @param aiTool The AI tool identifier.
     * @returns True if locked, false otherwise.
     */
    private isLocked(rootPath: string, aiTool: string): boolean {
        const lockFile = this.getLockPath(rootPath, aiTool);
        if (fs.existsSync(lockFile)) {
            // Check staleness (3 mins)
            const stat = fs.statSync(lockFile);
            if (Date.now() - stat.mtimeMs > 3 * 60 * 1000) {
                return false; // Stale lock
            }
            return true;
        }
        return false;
    }

    /**
     * Attempts to acquire a lock for the given AI tool.
     * 
     * @param rootPath The project root path.
     * @param aiTool The AI tool identifier.
     * @returns True if the lock was successfully acquired, false otherwise.
     */
    private tryAcquireLock(rootPath: string, aiTool: string): boolean {
        const lockFile = this.getLockPath(rootPath, aiTool);
        if (fs.existsSync(lockFile)) {
            const stat = fs.statSync(lockFile);
            if (Date.now() - stat.mtimeMs < 3 * 60 * 1000) {
                return false; // Locked
            }
            // Stale, overwrite
        }
        try {
            fs.writeFileSync(lockFile, Date.now().toString());
            return true;
        } catch (e) {
            Logger.error(`Failed to acquire lock ${lockFile}`, e);
            return false;
        }
    }

    /**
     * Releases the lock for the given AI tool.
     * 
     * @param rootPath The project root path.
     * @param aiTool The AI tool identifier.
     */
    private releaseLock(rootPath: string, aiTool: string) {
        const lockFile = this.getLockPath(rootPath, aiTool);
        try {
            if (fs.existsSync(lockFile)) {
                fs.unlinkSync(lockFile);
            }
        } catch (e) {
            Logger.error(`Failed to release lock ${lockFile}`, e);
        }
    }

    private getRuleBuilder(targetConfig: AIConfig): RuleBuilder {
        if (targetConfig.strategy === 'single-file') {
            return new SingleFileRuleBuilder(targetConfig.ruleRoot);
        } else {
            return new FolderRuleBuilder(targetConfig.ruleRoot, targetConfig.extension);
        }
    }

    private updateSyncStates(
        rootPath: string,
        aiTools: Record<string, AIConfig>,
        latest: RuleStat,
        targetAi: string,
        targetState: SyncState | null,
        aiMaxMtimes: Record<string, number>
    ) {
        const targetRuleRoot = path.join(rootPath, aiTools[targetAi].ruleRoot);
        const newTargetState: SyncState = {
            lastModified: latest.mtime,
            isFromSynced: true,
            source: latest.ai,
            syncedTo: targetState?.syncedTo || []
        };
        LocalStorage.getInstance().setData(targetRuleRoot, newTargetState);

        const sourceRuleRoot = path.join(rootPath, aiTools[latest.ai].ruleRoot);
        const sourceState = this.getSyncState(sourceRuleRoot, aiMaxMtimes[latest.ai]) || {
            lastModified: latest.mtime,
            isFromSynced: false,
            source: undefined,
            syncedTo: []
        };

        if (!sourceState.syncedTo.includes(targetAi)) {
            sourceState.syncedTo.push(targetAi);
        }
        LocalStorage.getInstance().setData(sourceRuleRoot, sourceState);
    }

    /**
     * Synchronizes rules from the source AI tool to the target AI tool.
     * 
     * @param sourceAi The identifier of the source AI tool.
     * @param targetAi The identifier of the target AI tool.
     * @param rootPath The project root path.
     * @returns A promise that resolves to true if changes were made, false otherwise.
     */
    private async syncRules(sourceAi: string, targetAi: string, rootPath: string): Promise<boolean> {
        const aiTools = AITool.getInstance().getAllAIConfigs();
        const sourceConfig = aiTools[sourceAi];
        const targetConfig = aiTools[targetAi];

        if (!sourceConfig || !targetConfig) return false;

        if (!this.isStrategyCompatible(sourceConfig, targetConfig, rootPath)) {
            return false;
        }

        const filePaths = this.listRuleFiles(rootPath, sourceConfig);
        const sources = this.readRuleFiles(filePaths);

        if (sources.length === 0) return false;

        // Build rules based on strategy
        const builder = this.getRuleBuilder(targetConfig);

        const ruleFiles = builder.buildRules(sources, sourceAi);

        // Write files
        const previousContents: Record<string, string | null> = {};
        let changed = false;

        for (const ruleFile of ruleFiles) {
            const targetPath = path.join(rootPath, ruleFile.path);

            try {
                let currentContent: string | null = null;
                if (fs.existsSync(targetPath)) {
                    currentContent = fs.readFileSync(targetPath, 'utf-8');
                }

                if (currentContent !== ruleFile.content) {
                    previousContents[ruleFile.path] = currentContent;

                    const targetDir = path.dirname(targetPath);
                    if (!fs.existsSync(targetDir)) {
                        fs.mkdirSync(targetDir, { recursive: true });
                    }

                    fs.writeFileSync(targetPath, ruleFile.content);
                    Logger.log(`Updated ${targetPath}`);
                    changed = true;
                }
            } catch (e) {
                Logger.error(`Failed to write ${targetPath}`, e);
            }
        }

        if (changed) {
            this.showRevertNotification(rootPath, previousContents, targetAi);
        }
        return changed;
    }

    private isStrategyCompatible(sourceConfig: AIConfig, targetConfig: AIConfig, rootPath: string): boolean {
        if (sourceConfig.strategy === 'single-file' && targetConfig.strategy === 'folder') {
            const existing = this.listRuleFiles(rootPath, targetConfig);
            if (existing.length > 1) {
                Logger.log(`Skipping sync: ${targetConfig.name} already has multiple rule files.`);
                return false;
            }
        }
        return true;
    }

    private showRevertNotification(rootPath: string, previousContents: Record<string, string | null>, targetAi: string) {
        vscode.window.showInformationMessage(`Rules synced to ${targetAi}`, 'Revert')
            .then(selection => {
                if (selection === 'Revert') {
                    try {
                        for (const [relativePath, content] of Object.entries(previousContents)) {
                            const targetPath = path.join(rootPath, relativePath);
                            if (content === null) {
                                if (fs.existsSync(targetPath)) {
                                    fs.unlinkSync(targetPath);
                                }
                            } else {
                                const targetDir = path.dirname(targetPath);
                                if (!fs.existsSync(targetDir)) {
                                    fs.mkdirSync(targetDir, { recursive: true });
                                }
                                fs.writeFileSync(targetPath, content);
                            }
                        }
                        Logger.log(`Reverted changes for ${targetAi}`);
                    } catch (e) {
                        Logger.error(`Failed to revert changes for ${targetAi}`, e);
                        vscode.window.showErrorMessage(`Failed to revert changes: ${e}`);
                    }
                }
            });
    }
}
