# One-IDE

One-IDE is a synchronization tool that allows you to use multiple IDEs (JetBrains IntelliJ-based IDEs and VS Code) as if they were a single unified environment.

## Features

- **Cross-IDE Synchronization**: Syncs active file and cursor position between VS Code and JetBrains IDEs.

## Architecture

The system consists of two plugins:

1. **VS Code Extension**: Watches editor changes (active tab, cursor) and writes to the operations log; watches the log for changes from other IDEs.
2. **JetBrains Plugin**: Performs the same role for IntelliJ IDEA, WebStorm, etc.

Both plugins share the `~/.one-ide` directory for storing the logs and configuration.

## Installation

### Prerequisites

- Node.js (for VS Code extension build)
- JDK 17+ (for JetBrains plugin build)

### Building VS Code Extension

```bash
cd vscode-extension
npm install
npm run compile
```

You can then load the extension in VS Code via "Run and Debug" or package it with `vsce package`.

### Building JetBrains Plugin

```bash
cd jetbrains-plugin
./gradlew buildPlugin
```

The plugin archive will be generated in `jetbrains-plugin/build/distributions`. Install it in your JetBrains IDE via "Install Plugin from Disk".

## Configuration

The configuration file is located at `~/.one-ide/config.json`. It is created automatically on first run.

Default configuration:

```json
{
  "excludeFiles": [],
  "excludeGitIgnore": true
}
```

- `excludeFiles`: Array of file names or patterns (e.g., `["*.log", "dist"]`) to ignore.
- `excludeGitIgnore`: If `true`, ignores files that are inside `.git` directories.