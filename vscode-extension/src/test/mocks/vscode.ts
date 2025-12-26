/**
 * Mock implementation of the VS Code API for testing purposes.
 * This avoids dependency on the actual VS Code environment during unit tests.
 */

export const workspace = {
    getWorkspaceFolder: () => null,
    workspaceFolders: [] as any[],
    getConfiguration: () => ({
        get: (key: string, defaultValue: any) => defaultValue
    }),
    onDidChangeConfiguration: () => ({ dispose: () => {} }),
    onDidOpenTextDocument: () => ({ dispose: () => {} }),
    onDidCloseTextDocument: () => ({ dispose: () => {} }),
    openTextDocument: async () => ({})
};
export const Uri = {
    file: (f: string) => ({ fsPath: f, scheme: 'file' })
};
export const window = {
    createStatusBarItem: () => ({ show: () => {}, dispose: () => {} }),
    createOutputChannel: () => ({ appendLine: () => {}, show: () => {}, dispose: () => {} }),
    showErrorMessage: () => {},
    showInformationMessage: () => {},
    onDidChangeActiveTextEditor: () => ({ dispose: () => {} }),
    onDidChangeTextEditorSelection: () => ({ dispose: () => {} }),
    onDidChangeVisibleTextEditors: () => ({ dispose: () => {} }),
    onDidChangeWindowState: () => ({ dispose: () => {} }),
    state: { focused: true },
    activeTextEditor: undefined as any,
    visibleTextEditors: [],
    tabGroups: { all: [], close: async () => {} },
    showTextDocument: async () => ({})
};
export const env = {
    appName: 'VSCode'
};
export const extensions = {
    getExtension: (id: string) => ({
        packageJSON: {
            version: '1.0.0'
        }
    })
};
export const commands = {
    executeCommand: async (command: string, ...rest: any[]) => {}
};
export enum StatusBarAlignment { Left, Right }
export enum ConfigurationTarget { Global = 1, Workspace = 2, WorkspaceFolder = 3 }
export class TabInputText {
    constructor(public uri: any) {}
}
export class Position {
    constructor(public line: number, public character: number) {}
}
export class Selection {
    constructor(public anchor: any, public active: any) {}
    isEqual() { return false; }
}
export class Range {
    constructor(public start: any, public end: any) {}
}
export enum TextEditorRevealType { InCenter = 1 }
