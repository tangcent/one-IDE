export interface FileState {
    filePath: string;
    cursor: number;
    column?: number;
    isActive: boolean;
}

export interface FolderState {
    path: string;
    openedFiles: FileState[];
    activeFile?: string;
    subFolders: FolderState[];
}

export interface State {
    timestamp: number;
    source: string;
    ide: 'jetbrains' | 'vscode';
    root: FolderState;
}

export interface Config {
    excludeFiles: string[];
    excludeGitIgnore: boolean;
}
