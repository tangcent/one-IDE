import { FolderState, FileState, State } from '../types';
import { IdeMetaData } from '../IdeMetaData';
import * as path from 'path';
import { PathUtils } from '../utils/PathUtils';

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
        const rootPathLower = PathUtils.normalizePath(rootPath);
        const activePathLower = activePath ? PathUtils.normalizePath(activePath) : undefined;

        const fileStates: FileState[] = openedFiles
            .filter(f => PathUtils.normalizePath(f.filePath).startsWith(rootPathLower))
            .map(f => {
                const fsPathLower = PathUtils.normalizePath(f.filePath);
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
     * Checks if two paths have an intersection (one is a parent of the other or they are the same).
     * 
     * @param pathOrState1 First path or State object
     * @param path2 Second path
     * @returns True if there is an intersection
     */
    public static hasIntersection(pathOrState1: string | State, path2: string): boolean {
        const p1Raw = typeof pathOrState1 === 'string' ? pathOrState1 : pathOrState1.root.path;
        const p1 = PathUtils.normalizePath(p1Raw);
        const p2 = PathUtils.normalizePath(path2);
        return p1.startsWith(p2) || p2.startsWith(p1);
    }

    /**
     * Checks if a path is inside the root path.
     * 
     * @param rootPath The root path
     * @param filePath The file path to check
     * @returns True if the file is inside the root
     */
    public static isInsideRoot(rootPath: string, filePath: string): boolean {
        const root = PathUtils.normalizePath(rootPath);
        const file = PathUtils.normalizePath(filePath);
        // Simple path comparison as requested, but ensuring directory boundary
        return file.startsWith(root) && (file.length === root.length || file[root.length] === path.sep);
    }

    /**
     * Checks if the given path belongs to the root of the state.
     * 
     * @param state The state object
     * @param filePath The file path to check
     * @returns True if the file belongs to the state root
     */
    public static checkPathBelongsToState(state: State, filePath: string): boolean {
        return StateHelper.isInsideRoot(state.root.path, filePath);
    }

    /**
     * Extracts a list of files from the state that belong to the given root path.
     * 
     * @param state The state object
     * @param rootPath The root path of the current workspace
     * @returns List of FileState objects
     */
    public static getFiles(state: State, rootPath: string): FileState[] {
        const normalizedRootPath = PathUtils.normalizePath(rootPath);

        if (!state.root || !state.root.openedFiles) {
            return [];
        }

        return state.root.openedFiles.filter(f => {
            try {
                const fPath = PathUtils.normalizePath(f.filePath);
                return fPath.startsWith(normalizedRootPath);
            } catch (e) {
                return false;
            }
        });
    }
}
