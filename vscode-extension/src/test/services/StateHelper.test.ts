import * as assert from 'assert';
import * as path from 'path';
import { StateHelper } from '../../services/StateHelper';
import { FileState, State } from '../../types';
import { IdeMetaData } from '../../IdeMetaData';

describe('StateHelper', () => {
    describe('buildState', () => {
        it('should build a flat state with normalized paths', () => {
            const rootPath = '/User/Project';
            const openedFiles: FileState[] = [
                { filePath: '/User/Project/src/main.ts', cursor: 10, column: 5, isActive: false },
                { filePath: '/User/Project/README.md', cursor: 0, column: 0, isActive: false }
            ];
            const activePath = '/User/Project/src/main.ts';

            const state = StateHelper.buildState(rootPath, openedFiles, activePath);

            const rootPathLower = path.resolve(rootPath).toLowerCase();
            assert.strictEqual(state.root.path, rootPathLower);
            assert.strictEqual(state.root.openedFiles.length, 2);

            const file1 = state.root.openedFiles.find(f => f.filePath.endsWith('main.ts'));
            assert.ok(file1);
            assert.strictEqual(file1?.isActive, true);
            assert.strictEqual(file1?.filePath, path.resolve('/User/Project/src/main.ts').toLowerCase());

            const file2 = state.root.openedFiles.find(f => f.filePath.endsWith('readme.md'));
            assert.ok(file2);
            assert.strictEqual(file2?.isActive, false);
            assert.strictEqual(file2?.filePath, path.resolve('/User/Project/README.md').toLowerCase());
        });

        it('should filter out files outside of root', () => {
            const rootPath = '/User/Project';
            const openedFiles: FileState[] = [
                { filePath: '/User/Project/in.ts', cursor: 0, isActive: false },
                { filePath: '/User/Other/out.ts', cursor: 0, isActive: false }
            ];

            const state = StateHelper.buildState(rootPath, openedFiles, undefined);
            assert.strictEqual(state.root.openedFiles.length, 1);
            assert.ok(state.root.openedFiles[0].filePath.includes('in.ts'));
        });
    });

    describe('getFiles', () => {
        it('should return files belonging to root', () => {
            const rootPath = '/User/Project';
            const state: State = {
                timestamp: Date.now(),
                source: 'test',
                ide: 'vscode',
                root: {
                    path: path.resolve(rootPath).toLowerCase(),
                    openedFiles: [
                        { filePath: path.resolve('/User/Project/file1.ts').toLowerCase(), cursor: 0, isActive: true },
                        { filePath: path.resolve('/User/Other/file2.ts').toLowerCase(), cursor: 0, isActive: false }
                    ]
                }
            };

            const files = StateHelper.getFiles(state, rootPath);
            assert.strictEqual(files.length, 1);
            assert.ok(files[0].filePath.endsWith('file1.ts'));
        });

        it('should handle empty state', () => {
            const state: State = {
                timestamp: Date.now(),
                source: 'test',
                ide: 'vscode',
                root: {
                    path: '/root',
                    openedFiles: []
                }
            };
            const files = StateHelper.getFiles(state, '/root');
            assert.strictEqual(files.length, 0);
        });
    });
});
