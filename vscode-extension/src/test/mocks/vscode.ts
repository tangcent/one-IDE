export const workspace = {
    getWorkspaceFolder: () => null,
    workspaceFolders: [],
    getConfiguration: () => ({
        get: (key: string, defaultValue: any) => defaultValue
    }),
    onDidChangeConfiguration: () => ({ dispose: () => {} })
};
export const Uri = {
    file: (f: string) => ({ fsPath: f, scheme: 'file' })
};
export const window = {
    createStatusBarItem: () => ({ show: () => {}, dispose: () => {} }),
    createOutputChannel: () => ({ appendLine: () => {}, show: () => {}, dispose: () => {} }),
    showErrorMessage: () => {},
    showInformationMessage: () => {}
};
export const env = {
    appName: 'VSCode'
};
export enum StatusBarAlignment { Left, Right }
export enum ConfigurationTarget { Global = 1, Workspace = 2, WorkspaceFolder = 3 }
