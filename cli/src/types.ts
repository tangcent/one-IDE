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
  ide: string;
  root: string;
  editorState: EditorState;
}

export interface ClusterState {
  timestamp: number;
  leaderId: string;
  state: State;
}
