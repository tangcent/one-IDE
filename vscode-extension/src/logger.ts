import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { IdeMetaData } from './IdeMetaData';

export class Logger {
    private static _outputChannel: vscode.OutputChannel;
    private static _logFile: string;
    private static _initialized = false;

    private static init() {
        if (this._initialized) return;
        
        const oneIdeDir = path.join(os.homedir(), '.one-ide');
        if (!fs.existsSync(oneIdeDir)) {
            try {
                fs.mkdirSync(oneIdeDir);
            } catch (e) {
                // ignore
            }
        }
        this._logFile = path.join(oneIdeDir, 'one-ide.log');
        this.rotateLogIfNeeded();
        this._initialized = true;
    }

    private static rotateLogIfNeeded() {
        try {
            if (fs.existsSync(this._logFile)) {
                const stats = fs.statSync(this._logFile);
                if (stats.size > 5 * 1024 * 1024) { // 5MB
                    const timestamp = new Date().toISOString().replace(/:/g, '-').split('.')[0];
                    const backup = `${this._logFile}.${timestamp}`;
                    fs.renameSync(this._logFile, backup);
                    
                    // Clean up old logs (keep last 5)
                    const dir = path.dirname(this._logFile);
                    const files = fs.readdirSync(dir)
                        .filter(f => f.startsWith('one-ide.log.'))
                        .sort()
                        .reverse();
                    
                    for (let i = 5; i < files.length; i++) {
                        fs.unlinkSync(path.join(dir, files[i]));
                    }
                }
            }
        } catch (e) {
            console.error('Failed to rotate logs:', e);
        }
    }

    private static writeToFile(message: string) {
        try {
            this.init();
            fs.appendFileSync(this._logFile, message + '\n');
        } catch (e) {
            // Fallback to console if file write fails
            console.error('Failed to write to log file:', e);
        }
    }

    public static get outputChannel(): vscode.OutputChannel {
        if (!this._outputChannel) {
            this._outputChannel = vscode.window.createOutputChannel("One-IDE");
        }
        return this._outputChannel;
    }

    private static _meta?: IdeMetaData;

    public static setMetaData(meta: IdeMetaData) {
        this._meta = meta;
    }

    public static log(message: string, ...args: any[]): void {
        const timestamp = new Date().toISOString();
        const formattedArgs = args.map(arg => {
            if (arg instanceof Error) {
                return arg.stack || arg.message;
            }
            if (typeof arg === 'object') {
                try {
                    return JSON.stringify(arg);
                } catch (e) {
                    return '[Object]';
                }
            }
            return arg;
        }).join(' ');
        
        const prefix = this._meta ? `[${this._meta.appName || this._meta.ide}-${this._meta.id}]` : `[VSCode]`;
        const formattedMessage = `[${timestamp}] [INFO] ${prefix} ${message} ${formattedArgs}`;
        console.log(formattedMessage); // Keep console log for DevTools
        this.outputChannel.appendLine(formattedMessage);
        this.writeToFile(formattedMessage);
    }

    public static error(message: string, ...args: any[]): void {
        const timestamp = new Date().toISOString();
        const formattedArgs = args.map(arg => {
            if (arg instanceof Error) {
                return arg.stack || arg.message;
            }
            if (typeof arg === 'object') {
                try {
                    return JSON.stringify(arg);
                } catch (e) {
                    return '[Object]';
                }
            }
            return arg;
        }).join(' ');

        const prefix = this._meta ? `[${this._meta.appName || this._meta.ide}-${this._meta.id}]` : `[VSCode]`;
        const formattedMessage = `[${timestamp}] [ERROR] ${prefix} ${message} ${formattedArgs}`;
        console.error(formattedMessage);
        this.outputChannel.appendLine(formattedMessage);
        this.writeToFile(formattedMessage);
        // Optional: show channel on error, but might be annoying
        // this.outputChannel.show(true); 
    }
    
    public static show(): void {
        this.outputChannel.show();
    }
}
