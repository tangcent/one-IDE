import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { StateService } from '../../services/StateService';
import { State } from '../../types';

describe('StateService', () => {
    let tmpDir: string;
    let oneIdeDir: string;
    let service: StateService;

    beforeEach(() => {
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
                timestamp: Date.now(),
                source: 'test-source',
                ide: 'vscode',
                root: { path: '/', openedFiles: [], subFolders: [] }
            };

            service.publishState(state, 'leader-id');
        }, 200);
    });
});
