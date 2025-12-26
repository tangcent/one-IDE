import * as assert from 'assert';
import * as path from 'path';
import { StateHelper } from '../../services/StateHelper';
import { ActiveFile, State } from '../../types';
import { IdeMetaData } from '../../IdeMetaData';

/**
 * Unit tests for StateHelper utility.
 */
describe('StateHelper', () => {
    describe('buildState', () => {
        it('should build a flat state with normalized paths', () => {
            const rootPath = '/User/Project';
            const openedFiles: string[] = [
                '/User/Project/src/main.ts',
                '/User/Project/README.md'
            ];
            const activeFile: ActiveFile = {
                filePath: '/User/Project/src/main.ts',
                cursor: 10,
                column: 5
            };

            const state = StateHelper.buildState(rootPath, openedFiles, activeFile);

            const rootPathLower = path.resolve(rootPath).toLowerCase();
            assert.strictEqual(state.root, rootPathLower);
            assert.strictEqual(state.editorState.openedFiles.length, 2);

            const file1 = state.editorState.openedFiles.find(f => f.endsWith('main.ts'));
            assert.ok(file1);
            
            const file2 = state.editorState.openedFiles.find(f => f.endsWith('readme.md'));
            assert.ok(file2);

            const active = state.editorState.activeFile;
            assert.ok(active);
            assert.strictEqual(active?.filePath, path.resolve('/User/Project/src/main.ts').toLowerCase());
            assert.strictEqual(active?.cursor, 10);
            assert.strictEqual(active?.column, 5);
        });

        it('should filter out files outside of root', () => {
            const rootPath = '/User/Project';
            const openedFiles: string[] = [
                '/User/Project/in.ts',
                '/User/Other/out.ts'
            ];

            const state = StateHelper.buildState(rootPath, openedFiles, undefined);
            assert.strictEqual(state.editorState.openedFiles.length, 1);
            assert.ok(state.editorState.openedFiles[0].includes('in.ts'));
        });
    });

    describe('getFiles', () => {
        it('should return files belonging to root', () => {
            const rootPath = '/User/Project';
            const state: State = {
                timestamp: Date.now(),
                source: 'test',
                ide: 'vscode',
                root: path.resolve(rootPath).toLowerCase(),
                editorState: {
                    openedFiles: [
                        path.resolve('/User/Project/file1.ts').toLowerCase(),
                        path.resolve('/User/Other/file2.ts').toLowerCase()
                    ],
                    activeFile: undefined
                }
            };

            const files = StateHelper.getFiles(state, rootPath);
            assert.strictEqual(files.length, 1);
            assert.ok(files[0].endsWith('file1.ts'));
        });

        it('should handle empty state', () => {
            const state: State = {
                timestamp: Date.now(),
                source: 'test',
                ide: 'vscode',
                root: '/root',
                editorState: {
                    openedFiles: [],
                    activeFile: undefined
                }
            };
            const files = StateHelper.getFiles(state, '/root');
            assert.strictEqual(files.length, 0);
        });
    });

    describe('isInsideRoot', () => {
        it('should return true if file is inside root', () => {
            const root = '/User/Project';
            const file = '/User/Project/src/file.ts';
            assert.strictEqual(StateHelper.isInsideRoot(root, file), true);
        });

        it('should return false if file is outside root', () => {
            const root = '/User/Project';
            const file = '/User/Other/file.ts';
            assert.strictEqual(StateHelper.isInsideRoot(root, file), false);
        });

        it('should return false for partial directory match', () => {
            const root = '/User/Project';
            const file = '/User/ProjectSuffix/file.ts';
            assert.strictEqual(StateHelper.isInsideRoot(root, file), false);
        });
    });

    describe('checkPathBelongsToState', () => {
        it('should check against state root', () => {
            const rootPath = '/User/Project';
            const state: State = {
                timestamp: Date.now(),
                source: 'test',
                ide: 'vscode',
                root: path.resolve(rootPath).toLowerCase(),
                editorState: {
                    openedFiles: [],
                    activeFile: undefined
                }
            };
            assert.strictEqual(StateHelper.checkPathBelongsToState(state, '/User/Project/file.ts'), true);
            assert.strictEqual(StateHelper.checkPathBelongsToState(state, '/User/Other/file.ts'), false);
        });
    });

    describe('hasIntersection', () => {
        it('should work with two strings', () => {
            assert.strictEqual(StateHelper.hasIntersection('/User/Project', '/User/Project/Sub'), true);
            assert.strictEqual(StateHelper.hasIntersection('/User/Project/Sub', '/User/Project'), true);
            assert.strictEqual(StateHelper.hasIntersection('/User/Project', '/User/Other'), false);
        });

        it('should work with state object', () => {
            const rootPath = '/User/Project';
            const state: State = {
                timestamp: Date.now(),
                source: 'test',
                ide: 'vscode',
                root: path.resolve(rootPath).toLowerCase(),
                editorState: {
                    openedFiles: [],
                    activeFile: undefined
                }
            };
            assert.strictEqual(StateHelper.hasIntersection(state, '/User/Project/Sub'), true);
            assert.strictEqual(StateHelper.hasIntersection(state, '/User/Other'), false);
        });
    });
});
