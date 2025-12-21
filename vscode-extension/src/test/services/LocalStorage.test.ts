import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { LocalStorage } from '../../services/LocalStorage';

describe('LocalStorage Service', () => {
    let tempDir: string;
    let tempFile: string;
    let localStorage: LocalStorage;

    beforeEach(() => {
        tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'one-ide-test-'));
        tempFile = path.join(tempDir, 'test-storage.json');
        localStorage = LocalStorage.getInstance();
        localStorage.setStorageFile(tempFile);
    });

    afterEach(() => {
        if (fs.existsSync(tempDir)) {
            fs.rmSync(tempDir, { recursive: true, force: true });
        }
    });

    it('should set and get data', () => {
        const key = 'testKey';
        const value = 'testValue';

        localStorage.setData(key, value);
        const retrieved = localStorage.getData<string>(key);

        assert.strictEqual(retrieved, value);
    });

    it('should update data', () => {
        const key = 'updateKey';
        const value = 'initial';

        localStorage.setData(key, value);
        localStorage.updateData<string>(key, (old) => old + 'Updated');

        const retrieved = localStorage.getData<string>(key);
        assert.strictEqual(retrieved, 'initialUpdated');
    });

    it('should delete data', () => {
        const key = 'deleteKey';
        const value = 'deleteValue';

        localStorage.setData(key, value);
        assert.strictEqual(localStorage.getData(key), value);

        localStorage.deleteData(key);
        assert.strictEqual(localStorage.getData(key), undefined);
    });

    it('should handle withLock modification', () => {
        const key = 'lockKey';
        const value = 'lockValue';

        localStorage.setData(key, value);

        localStorage.withLock(false, (map) => {
            assert.strictEqual(map[key], value);
            map[key] = 'newValue';
        });

        const retrieved = localStorage.getData<string>(key);
        assert.strictEqual(retrieved, 'newValue');
    });

    it('should handle withLock read-only', () => {
        const key = 'readKey';
        const value = 'readValue';

        localStorage.setData(key, value);

        localStorage.withLock(true, (map) => {
            assert.strictEqual(map[key], value);
            map[key] = 'ignoredValue'; // Should not be persisted
        });

        const retrieved = localStorage.getData<string>(key);
        assert.strictEqual(retrieved, value);
    });
});
