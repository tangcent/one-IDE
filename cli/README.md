# one-ide-cli

CLI to access live IDE editor state published by the [One-IDE](https://github.com/tangcent/one-IDE) plugin.

When running AI tools in the terminal (Claude CLI, Codex, Kiro CLI, etc.), the tool has no direct access to the IDE. The One-IDE plugin (JetBrains / VS Code) continuously writes editor state to `~/.one-ide/cluster/state.json`. `one-ide-cli` reads that file and exposes the data as simple commands.

## Installation

```bash
npm install -g one-ide-cli
```

## Prerequisites

The [One-IDE plugin](https://github.com/tangcent/one-IDE) must be installed and active in at least one IDE (JetBrains or VS Code/fork).

## Commands

### Active project root

```bash
one-ide-cli active-project
# /Users/you/project
```

### Active file in a project

```bash
one-ide-cli active-file /Users/you/project
# /Users/you/project/src/main.ts
```

Returns the file currently focused in the IDE. Exits with an error if the given project path does not match the active project.

### Open files in a project

```bash
one-ide-cli opened-files /Users/you/project
```

```json
[
  "/Users/you/project/src/main.ts",
  "/Users/you/project/src/utils.ts",
  "/Users/you/project/README.md"
]
```

### Active IDE name

```bash
one-ide-cli ide
# VSCode
```

### Help

```bash
one-ide-cli --help
```

## Using Editor Context in AI Prompts

```bash
# Pass active file to Claude CLI
PROJECT=$(one-ide-cli active-project)
ACTIVE=$(one-ide-cli active-file "$PROJECT")
claude "Review $ACTIVE for potential bugs."

# Pass all open files as context
PROJECT=$(one-ide-cli active-project)
FILES=$(one-ide-cli opened-files "$PROJECT")
claude "I have these files open: $FILES. Help me understand the overall structure."

# Kiro CLI
PROJECT=$(one-ide-cli active-project)
ACTIVE=$(one-ide-cli active-file "$PROJECT")
kiro "Review the file at $ACTIVE"
```
