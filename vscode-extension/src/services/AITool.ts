import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { Logger } from '../logger';
import { IdeMetaData } from '../IdeMetaData';
import { ConfigService } from './ConfigService';

/**
 * Interface representing the configuration for a specific AI tool.
 */
export interface AIConfig {
    /** The display name of the AI tool (e.g., "Cursor", "Trae"). */
    name: string;
    /** List of glob patterns to identify rule files associated with this tool. */
    patterns: string[];
    /** The root directory or file path where rules for this tool are stored. */
    ruleRoot: string;
    /** The rule syncing strategy: "folder" (syncs entire directory) or "single-file". */
    strategy: 'folder' | 'single-file';
    /** List of plugin IDs (extensions) associated with this tool for detection purposes. */
    plugins: string[];
}

/**
 * Interface representing the JSON structure of the `ai-tools.json` configuration file.
 */
interface AIConfigJson {
    tools: AIConfig[];
}

/**
 * Service for managing AI tools configuration and detection.
 * 
 * This service loads supported AI tools from `ai-tools.json` and provides functionality
 * to detect the currently active AI tool based on user configuration, IDE environment, or installed extensions.
 */
export class AITool {
    private static instance: AITool;
    private aiTools: Record<string, AIConfig> = {};
    private configService: ConfigService;
    private context: vscode.ExtensionContext;

    private constructor(context: vscode.ExtensionContext, configService: ConfigService) {
        this.context = context;
        this.configService = configService;
        this.loadAIConfig();
    }

    /**
     * Initializes the singleton instance of AITool.
     * 
     * @param context The extension context.
     * @param configService The configuration service instance.
     * @returns The singleton instance of AITool.
     */
    public static initialize(context: vscode.ExtensionContext, configService: ConfigService): AITool {
        if (!AITool.instance) {
            AITool.instance = new AITool(context, configService);
        }
        return AITool.instance;
    }

    /**
     * Retrieves the singleton instance of AITool.
     * 
     * @returns The singleton instance of AITool.
     * @throws Error if AITool is not initialized.
     */
    public static getInstance(configService?: ConfigService): AITool {
        if (!AITool.instance) {
            // Backward compatibility for tests or if initialized improperly, though this path is deprecated
             if (configService) {
                 Logger.error("AITool.getInstance called with ConfigService but without context. This is deprecated and may fail to load resources.");
                 // We can't properly initialize without context for file paths
                 throw new Error("AITool must be initialized with context first using initialize()");
             }
            throw new Error("AITool not initialized. Call initialize() first.");
        }
        return AITool.instance;
    }

    /**
     * Loads AI tool configurations from the `ai-tools.json` resource file.
     */
    private loadAIConfig() {
        try {
            const configPath = path.join(this.context.extensionPath, 'ai-tools.json');
            if (fs.existsSync(configPath)) {
                const content = fs.readFileSync(configPath, 'utf-8');
                const json: AIConfigJson = JSON.parse(content);
                for (const tool of json.tools) {
                    this.aiTools[tool.name] = tool;
                }
            } else {
                Logger.error('ai-tools.json not found at ' + configPath);
            }
        } catch (e) {
            Logger.error('Failed to load ai-tools.json', e);
        }
    }

    /**
     * Retrieves the configuration for a specific AI tool by name.
     * 
     * @param name The name of the AI tool.
     * @returns The configuration object if found, undefined otherwise.
     */
    public getAIConfig(name: string): AIConfig | undefined {
        return this.aiTools[name];
    }

    /**
     * Returns a map of all configured AI tools.
     * 
     * @returns A record where the key is the tool name and the value is the configuration object.
     */
    public getAllAIConfigs(): Record<string, AIConfig> {
        return this.aiTools;
    }

    /**
     * Detects the currently active AI tool.
     * 
     * Detection logic:
     * 1. Checks the user's configuration. If a specific tool is selected (not "Auto"), it is returned.
     * 2. If "Auto", checks if the current IDE app name matches any tool name (e.g., running in Cursor or Trae IDE).
     * 3. Checks if any associated extensions are installed.
     * 
     * @returns The name of the detected tool, or undefined if no tool is detected.
     */
    public detectCurrentTool(): string | undefined {
        const configuredTool = this.configService.getConfig().currentTool;
        const appName = IdeMetaData.getInstance().appName;
        
        return this.resolveTool(
            configuredTool, 
            appName, 
            (id) => !!vscode.extensions.getExtension(id)
        );
    }

    /**
     * Resolves the current AI tool based on configuration, app name, and installed plugins.
     * Exposed for testing purposes to bypass static/global dependencies.
     * 
     * @param configuredTool The tool configured by the user (e.g., "Auto", "Cursor").
     * @param appName The name of the current IDE application.
     * @param isPluginInstalled A predicate function to check if a plugin with a given ID is installed.
     * @returns The name of the detected tool, or undefined if no tool is detected.
     */
    public resolveTool(
        configuredTool: string | undefined, 
        appName: string, 
        isPluginInstalled: (id: string) => boolean
    ): string | undefined {
        Logger.log(`Resolving tool. Configured: ${configuredTool}, AppName: ${appName}`);

        // 1. User Config
        if (configuredTool && configuredTool !== 'Auto') {
            if (this.aiTools[configuredTool]) {
                Logger.log(`Using configured tool: ${configuredTool}`);
                return configuredTool;
            }
        }

        // 2. IDE Fork Detection
        const lowerAppName = appName.toLowerCase();
        for (const tool of Object.values(this.aiTools)) {
            // Avoid matching generic names if any (e.g. "Code")
            if (lowerAppName.includes(tool.name.toLowerCase())) {
                 Logger.log(`Detected tool via app name: ${tool.name}`);
                 return tool.name;
            }
        }

        // 3. Plugin Detection
        for (const tool of Object.values(this.aiTools)) {
            if (tool.plugins && tool.plugins.length > 0) {
                for (const pluginId of tool.plugins) {
                    if (isPluginInstalled(pluginId)) {
                        Logger.log(`Detected installed plugin ${pluginId} for ${tool.name}`);
                        return tool.name;
                    }
                }
            }
        }

        Logger.log('No tool detected.');
        return undefined;
    }
}
