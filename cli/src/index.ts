#!/usr/bin/env node
import * as fs from 'fs';
import { readState } from './state.js';
import { printHelp } from './help.js';

const args = process.argv.slice(2);

if (args.length === 0 || args.includes('--help') || args.includes('-h')) {
  printHelp();
  process.exit(0);
}

const [cmd, projectPath] = args;

switch (cmd) {
  case 'active-project': {
    const state = readState();
    if (!state.root) {
      console.error('No active project in current state.');
      process.exit(2);
    }
    console.log(state.root);
    break;
  }

  case 'active-file': {
    if (!projectPath) {
      console.error('Usage: one-ide-cli active-file <project-path>');
      process.exit(1);
    }
    const state = readState();
    if (state.root && normalizePath(state.root) !== normalizePath(projectPath)) {
      console.error(`Project "${projectPath}" is not the active project (active: ${state.root}).`);
      process.exit(2);
    }
    const activeFile = state.editorState?.activeFile;
    if (!activeFile?.filePath) {
      console.error('No active file in current state.');
      process.exit(2);
    }
    console.log(activeFile.filePath);
    break;
  }

  case 'opened-files': {
    if (!projectPath) {
      console.error('Usage: one-ide-cli opened-files <project-path>');
      process.exit(1);
    }
    const state = readState();
    if (state.root && normalizePath(state.root) !== normalizePath(projectPath)) {
      console.error(`Project "${projectPath}" is not the active project (active: ${state.root}).`);
      process.exit(2);
    }
    const files = state.editorState?.openedFiles ?? [];
    console.log(JSON.stringify(files, null, 2));
    break;
  }

  case 'context': {
    if (!projectPath) {
      console.error('Usage: one-ide-cli context <project-path>');
      process.exit(1);
    }
    const state = readState();
    if (state.root && normalizePath(state.root) !== normalizePath(projectPath)) {
      console.error(`Project "${projectPath}" is not the active project (active: ${state.root}).`);
      process.exit(2);
    }
    const activeFile = state.editorState?.activeFile;
    const filePath = activeFile?.filePath || null;

    let activeFileText: string | null = null;
    let activeSelectionText: string | null = null;

    if (filePath && fs.existsSync(filePath)) {
      activeFileText = fs.readFileSync(filePath, 'utf-8');

      if (activeFile?.selectionEndCursor != null && activeFileText) {
        const lines = activeFileText.split('\n');
        const startLine = (activeFile.cursor ?? 1) - 1;
        const startCol = activeFile.column ?? 0;
        const endLine = activeFile.selectionEndCursor - 1;
        const endCol = activeFile.selectionEndColumn ?? 0;

        if (startLine === endLine) {
          activeSelectionText = lines[startLine]?.slice(startCol, endCol) ?? null;
        } else {
          const parts = [lines[startLine]?.slice(startCol) ?? ''];
          for (let i = startLine + 1; i < endLine; i++) parts.push(lines[i] ?? '');
          parts.push(lines[endLine]?.slice(0, endCol) ?? '');
          activeSelectionText = parts.join('\n');
        }
      }
    }

    const result: Record<string, unknown> = {
      activeProject: state.root || null,
      activeFile: filePath,
      activeSelection: (activeFile?.selectionEndCursor != null)
        ? {
            startLine: activeFile.cursor,
            startColumn: activeFile.column,
            endLine: activeFile.selectionEndCursor,
            endColumn: activeFile.selectionEndColumn,
          }
        : null,
      openedFiles: state.editorState?.openedFiles ?? [],
      activeFileText,
      activeSelectionText,
    };
    console.log(JSON.stringify(result, null, 2));
    break;
  }

  case 'ide': {
    const state = readState();
    console.log(state.ide || 'unknown');
    break;
  }

  default:
    console.error(`Unknown command: ${cmd}`);
    printHelp();
    process.exit(1);
}

function normalizePath(p: string): string {
  return p.replace(/\\/g, '/').toLowerCase();
}
