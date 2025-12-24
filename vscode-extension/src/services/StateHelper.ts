import { FolderState, FileState, State } from '../types';
import { IdeMetaData } from '../IdeMetaData';
import * as path from 'path';

export class StateHelper {
    /**
     * Builds a flattened State object where all opened files are directly under the root FolderState.
     * All paths are converted to lowercase and normalized.
     * 
     * @param rootPath The root path of the workspace
     * @param openedFiles List of opened files with their cursor/selection info
     * @param activePath The currently active file path
     * @returns A State object
     */
    public static buildState(rootPath: string, openedFiles: FileState[], activePath: string | undefined): State {
        const rootPathLower = this.normalizePath(rootPath);
        const activePathLower = activePath ? this.normalizePath(activePath) : undefined;

        const fileStates: FileState[] = openedFiles
            .filter(f => this.normalizePath(f.filePath).startsWith(rootPathLower))
            .map(f => {
                const fsPathLower = this.normalizePath(f.filePath);
                return {
                    filePath: fsPathLower,
                    cursor: f.cursor,
                    column: f.column,
                    isActive: fsPathLower === activePathLower
                };
            });

        const rootNode: FolderState = {
            path: rootPathLower,
            openedFiles: fileStates
        };

        const meta = IdeMetaData.getInstance();
        return {
            timestamp: Date.now(),
            source: meta.id,
            ide: meta.ide,
            root: rootNode
        };
    }

    /**
     * Extracts a list of files from the state that belong to the given root path.
     * 
     * @param state The state object
     * @param rootPath The root path of the current workspace
     * @returns List of FileState objects
     */
    public static getFiles(state: State, rootPath: string): FileState[] {
        const normalizedRootPath = this.normalizePath(rootPath);

        if (!state.root || !state.root.openedFiles) {
            return [];
        }

        return state.root.openedFiles.filter(f => {
            try {
                const fPath = this.normalizePath(f.filePath);
                return fPath.startsWith(normalizedRootPath);
            } catch (e) {
                return false;
            }
        });
    }

    private static normalizePath(p: string): string {
        return path.resolve(p).toLowerCase();
    }
}
