import * as path from 'path';

export class PathUtils {
    /**
     * Normalizes a path by resolving it to an absolute path and converting it to lowercase.
     * This is useful for consistent path comparisons, especially on case-insensitive file systems.
     * 
     * @param p The path to normalize
     * @returns The normalized path
     */
    public static normalizePath(p: string): string {
        return path.resolve(p).toLowerCase();
    }
}
