import * as vscode from 'vscode';

export class IdeMetaData {
    private static _instance: IdeMetaData;
    public readonly id: string;
    public readonly ide: 'vscode' | 'jetbrains' = 'vscode';
    public readonly appName: string;
    public lastCheckPoint: number = 0;

    private constructor() {
        // random 6 characters
        this.id = Math.random().toString(36).substring(2, 8);
        this.appName = vscode.env.appName;
    }

    public static getInstance(): IdeMetaData {
        if (!this._instance) {
            this._instance = new IdeMetaData();
        }
        return this._instance;
    }
}
