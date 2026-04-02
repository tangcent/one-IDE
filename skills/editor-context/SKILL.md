---
name: editor-context
description: Bridges the gap between terminal AI tools and the IDE editor. Activate when user says "this file", "this function", "this class", "explain this", "current file", "selected code", "what I have open", or asks about code without providing it. Skip if the AI already has the file content or path in context. Works with VS Code, Cursor, JetBrains IDEs and their forks via the One-IDE plugin.
---

# Editor Context via one-ide-cli

Run the following to detect the current editor context:

```bash
one-ide-cli context /path/to/current/workspace
```

## When to activate

**Always run on the first coding turn of a new session** — before answering, fetch context so the response is grounded in what the user actually has open.

Also activate when the question is code-adjacent and the active file is unknown. Trigger phrases include:
- explicit: "this file", "current file", "selected code", "what I have open"
- implicit: "this", "that", "it", "this field", "this function", "this class", "what does this mean", "explain this", "why is this"

**Skip only when:**
- The AI already has the file content or path in context (e.g. user pasted code directly)
- The question clearly refers only to pasted content with no ambiguity

## Output

```json
{
  "activeProject": "/Users/you/project",
  "activeFile": "/Users/you/project/src/main.ts",
  "activeSelection": {
    "startLine": 10,
    "startColumn": 0,
    "endLine": 20,
    "endColumn": 5
  },
  "openedFiles": [
    "/Users/you/project/src/main.ts",
    "/Users/you/project/src/utils.ts"
  ],
  "activeFileText": "...full file content...",
  "activeSelectionText": "...selected text..."
}
```

- `activeFile` — absolute path of the focused file, `null` if no file is open
- `activeSelection` — line/column range of selected text, `null` if nothing is selected
- `activeFileText` — full content of the active file, `null` if no file is open
- `activeSelectionText` — the selected text, `null` if nothing is selected
- `openedFiles` — all open editor tabs, empty array if none

## Compatibility

Works with any IDE that has the [One-IDE plugin](https://github.com/tangcent/one-IDE) installed: VS Code, Cursor, Windsurf, JetBrains IDEs (IntelliJ, WebStorm, PyCharm, etc.) and their forks.

> If `one-ide-cli` is not found, install it: `npm install -g one-ide-cli`

## Other Commands

```bash
one-ide-cli active-project              # workspace root of the active project
one-ide-cli active-file <project-path>  # active file in the given project
one-ide-cli opened-files <project-path> # all open files in the given project (JSON array)
one-ide-cli ide                         # active IDE name (e.g. VSCode, IntelliJ IDEA)
one-ide-cli --help
```
