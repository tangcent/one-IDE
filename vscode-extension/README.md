# One-IDE VS Code Extension

This extension synchronizes file operations (Open, Close, Cursor Move) between VS Code and other IDEs (like IntelliJ IDEA) using the One-IDE protocol.

## Features

- **File Synchronization**: Automatically opens files opened in other IDEs.
- **Cursor Synchronization**: Shows cursor position from other IDEs.
- **Status Bar Integration**: Quickly toggle synchronization on/off.

## Installation

1. Clone the repository.
2. Open the `vscode-extension` folder in VS Code.
3. Run `npm install` to install dependencies.
4. Run `npm run compile` to build the extension.
5. Press `F5` to start debugging, or package it using `vsce package`.

## Usage

- The extension starts automatically.
- Click the "One-IDE" status bar item to toggle synchronization.
- Logs are stored in `~/.one-ide/operations.log`.

## Configuration

No additional configuration is required. The extension uses the `~/.one-ide` directory for synchronization.
