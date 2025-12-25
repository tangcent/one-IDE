export interface ActiveFile {
    filePath: string;
    cursor: number;
    column?: number;
}

export interface FolderState {
    path: string;
    openedFiles: string[];
    activeFile?: ActiveFile;
}

export interface State {
    timestamp: number;
    source: string;
    ide: 'jetbrains' | 'vscode';
    root: FolderState;
}

export interface Config {
    // Global (from config.json)
    excludeFiles: string[];
    excludeGitIgnore: boolean;

    // Project (from IDE settings)
    syncRules: boolean;
    currentTool: string;
}

export interface NodeInfo {
    id: string;
    timestamp: number;
    lastHeartbeat?: number;
}

export interface ClusterState {
    timestamp: number;
    leaderId: string;
    state: State;
}

export interface CandidatesData {
    candidates: NodeInfo[];
}
