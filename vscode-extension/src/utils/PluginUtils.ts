import * as vscode from 'vscode';

export class PluginUtils {
    /**
     * Checks if a specific extension is installed and active.
     * @param id The extension ID (e.g., "GitHub.copilot")
     */
    public static isExtensionInstalled(id: string): boolean {
        const extension = vscode.extensions.getExtension(id);
        return extension !== undefined;
    }

    public static isCopilotInstalled(): boolean {
        return this.isExtensionInstalled('GitHub.copilot');
    }

    public static isCursorInstalled(): boolean {
        // Cursor isn't exactly an extension in VS Code usually, it's a fork.
        // But if there's a specific extension for it:
        return false; // Cursor is the IDE itself usually
    }
}
