export interface ActiveFile {
    filePath: string;
    cursor: number;
    column?: number;
    selectionEndCursor?: number;
    selectionEndColumn?: number;
}

export interface EditorState {
    openedFiles: string[];
    activeFile?: ActiveFile;
}

export interface State {
    timestamp: number;
    source: string;
    ide: 'jetbrains' | 'vscode';
    root: string;
    editorState: EditorState;
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
    pluginVersion?: string;
    ide?: string;
}

export interface ClusterState {
    timestamp: number;
    leaderId: string;
    state: State;
}

export interface CandidatesData {
    candidates: NodeInfo[];
}
