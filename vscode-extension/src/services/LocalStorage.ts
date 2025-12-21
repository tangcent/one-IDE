import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';

export class LocalStorage {
    private static instance: LocalStorage;
    private storageFile: string;
    private testFile: string | null = null;

    private constructor() {
        this.storageFile = path.join(os.homedir(), '.one-ide', 'local-storage.json');
    }

    public static getInstance(): LocalStorage {
        if (!LocalStorage.instance) {
            LocalStorage.instance = new LocalStorage();
        }
        return LocalStorage.instance;
    }

    public setStorageFile(file: string) {
        this.testFile = file;
        if (file) {
            const dir = path.dirname(file);
            if (!fs.existsSync(dir)) {
                fs.mkdirSync(dir, { recursive: true });
            }
        }
    }

    private getFile(): string {
        const file = this.testFile || this.storageFile;
        const dir = path.dirname(file);
        if (!fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
        }
        return file;
    }

    /**
     * Executes the given block within a file lock.
     * 
     * @param shared If true, acquires a shared lock (read-only) and does not write back.
     *               If false, acquires an exclusive lock (read-write) and writes changes back.
     * @param block The code block to execute with the map.
     */
    public withLock<R>(shared: Boolean, block: (map: Record<string, any>) => R): R | null {
        return this.executeWithLock(shared, (file) => {
            const content = this.readFile(file);
            let map: Record<string, any> = {};
            if (content) {
                try {
                    map = JSON.parse(content);
                } catch (e) {
                    // ignore
                }
            }

            const result = block(map);

            if (!shared) {
                this.writeFile(file, JSON.stringify(map, null, 2));
            }

            return result;
        });
    }

    /**
     * Retrieves data associated with the given key.
     * 
     * @param key The key to retrieve.
     */
    public getData<T>(key: string): T | null {
        return this.withLock(true, (map) => {
            return map[key] as T;
        });
    }

    /**
     * Sets data for the given key.
     * 
     * @param key The key to set.
     * @param data The data to store.
     */
    public setData<T>(key: string, data: T) {
        this.withLock(false, (map) => {
            map[key] = data;
        });
    }

    /**
     * Deletes data associated with the given key.
     * 
     * @param key The key to delete.
     */
    public deleteData(key: string) {
        this.withLock(false, (map) => {
            delete map[key];
        });
    }

    /**
     * Updates data associated with the given key using a transformation function.
     * 
     * @param key The key to update.
     * @param transform The transformation function.
     */
    public updateData<T>(key: string, transform: (old: T) => T) {
        this.withLock(false, (map) => {
            const current = map[key] as T;
            if (current !== undefined && current !== null) {
                map[key] = transform(current);
            }
        });
    }

    private readFile(file: string): string {
        if (!fs.existsSync(file)) return '';
        return fs.readFileSync(file, 'utf-8');
    }

    private writeFile(file: string, content: string) {
        fs.writeFileSync(file, content, 'utf-8');
    }

    private executeWithLock<R>(shared: Boolean, block: (file: string) => R): R | null {
        const file = this.getFile();
        const lockDir = path.join(path.dirname(file), 'local-storage.lock');
        const maxWait = 5000;
        const start = Date.now();

        while (Date.now() - start < maxWait) {
            try {
                fs.mkdirSync(lockDir);
                // Lock acquired
                try {
                    return block(file);
                } finally {
                    try {
                        fs.rmdirSync(lockDir);
                    } catch (e) {
                        // ignore unlock error
                    }
                }
            } catch (e) {
                // Failed to acquire lock
                // Check if lock is stale (older than 10s)
                try {
                    const stats = fs.statSync(lockDir);
                    if (Date.now() - stats.mtimeMs > 10000) {
                        try {
                            fs.rmdirSync(lockDir);
                        } catch (rmErr) {
                            // ignore
                        }
                    }
                } catch (statErr) {
                    // Lock dir might not exist anymore, continue loop
                }

                // Wait a bit
                const waitStart = Date.now();
                while (Date.now() - waitStart < 50) {
                    // busy wait or use Atomics if strictly needed, but simple loop is okay for this
                }
            }
        }
        return null;
    }
}
