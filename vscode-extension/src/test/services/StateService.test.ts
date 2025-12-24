import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { StateService } from '../../services/StateService';
import { IdeMetaData } from '../../IdeMetaData';
import { State } from '../../types';

describe('StateService', () => {
    let tmpDir: string;
    let oneIdeDir: string;
    let service: StateService;

    beforeEach(() => {
        IdeMetaData.getInstance().lastCheckPoint = 0;
        tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'one-ide-test-'));
        oneIdeDir = path.join(tmpDir, '.one-ide');
        service = new StateService(oneIdeDir);
    });

    afterEach(() => {
        service.dispose();
        fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it('should publish and receive state', (done) => {
        service.setOnStateReceived((state) => {
            try {
                assert.strictEqual(state.source, 'test-source');
                done();
            } catch (e) {
                done(e);
            }
        });

        service.startWatching();

        // Wait for watcher to start
        setTimeout(() => {
            const state: State = {
                timestamp: Date.now() - 1000,
                source: 'test-source',
                ide: 'vscode',
                root: { path: '/', openedFiles: [] }
            };

            service.publishState(state, 'leader-id');
        }, 200);
    });

    it('should publish state without cleaning', (done) => {
        const state: State = {
            timestamp: Date.now(),
            source: 'test-source',
            ide: 'vscode',
            root: {
                path: '/root',
                openedFiles: [
                    { filePath: 'file1', cursor: 0, column: 0, isActive: false },
                    { filePath: 'file2', cursor: 10, column: 5, isActive: true }
                ],
            }
        };

        service.publishState(state, 'leader-id');

        // Allow some time for file write
        setTimeout(() => {
            const stateFile = path.join(oneIdeDir, 'cluster', 'state.json');
            if (!fs.existsSync(stateFile)) {
                done(new Error('State file not created'));
                return;
            }
            const content = fs.readFileSync(stateFile, 'utf-8');
            const json = JSON.parse(content);

            try {
                // Check root
                assert.strictEqual(json.state.root.path, '/root');
                
                // Check openedFiles
                assert.strictEqual(json.state.root.openedFiles.length, 2);
                
                // Check file1
                const file1 = json.state.root.openedFiles[0];
                assert.strictEqual(file1.filePath, 'file1');
                assert.strictEqual(file1.cursor, 0);
                assert.strictEqual(file1.column, 0);
                assert.strictEqual(file1.isActive, false);
                
                // Check file2
                const file2 = json.state.root.openedFiles[1];
                assert.strictEqual(file2.filePath, 'file2');
                assert.strictEqual(file2.cursor, 10);
                assert.strictEqual(file2.column, 5);
                assert.strictEqual(file2.isActive, true);
                
                done();
            } catch (e) {
                done(e);
            }
        }, 100);
    });
});
