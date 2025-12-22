# One-IDE

[![CI](https://github.com/tangcent/one-IDE/actions/workflows/ci.yml/badge.svg)](https://github.com/tangcent/one-IDE/actions/workflows/ci.yml)
[![JetBrains Plugin Version](https://img.shields.io/jetbrains/plugin/v/29454?color=blue&label=JetBrains%20Plugin)](https://plugins.jetbrains.com/plugin/29454-one-ide)
[![JetBrains Plugin Downloads](https://img.shields.io/jetbrains/plugin/d/29454?label=Downloads)](https://plugins.jetbrains.com/plugin/29454-one-ide)
[![VS Code Extension Version](https://img.shields.io/visual-studio-marketplace/v/tangcent.one-ide?color=blue&label=VS%20Code%20Extension)](https://marketplace.visualstudio.com/items?itemName=tangcent.one-ide)
[![VS Code Extension Downloads](https://img.shields.io/visual-studio-marketplace/d/tangcent.one-ide?label=Downloads)](https://marketplace.visualstudio.com/items?itemName=tangcent.one-ide)

One-IDE is built for developers who work across multiple environments simultaneously. When you open a project in both a JetBrains IDE and VS Code (or its forks), your editors stay perfectly in sync—providing a seamless transition as you switch between tools.

 Beyond file tracking, the extension automatically detects and adapts AI project rules between platforms. For example, if you update project rules in Cursor, Trae will instantly detect the changes, convert them to its native format, and apply them to the correct location.

## Features

- **Cross-IDE Synchronization**: Syncs active file and cursor position between VS Code and JetBrains IDEs.
- **AI Project Rules Synchronization**: Automatically detects and syncs AI project rules (e.g., `.cursorrules`, `.trae/rules`) between different AI coding tools.

## Architecture

The system consists of two plugins that work together to synchronize editor state and AI project rules:

1. **VS Code Extension**: Supports VS Code and its forks (Cursor, Antigravity, etc.)
2. **JetBrains Plugin**: Supports JetBrains IDEs (IntelliJ IDEA, WebStorm, PyCharm, etc.)

Both plugins share the same global configuration. And all connected IDEs will sync their states and rules with each other.

## How Sync Works

The synchronization system is designed as a **distributed cluster** where all running IDE instances coordinate via a shared local directory (`~/.one-ide`).

### Core Concepts

1. **Leader-Follower Model**:
   - **Leader**: The currently active IDE instance (where you are typing or navigating). It is responsible for capturing the editor state (open files, cursor position) and publishing it to the cluster.
   - **Follower**: All other inactive IDE instances. They listen for state updates from the Leader and apply them to their local editors.
   - **Candidate**: A temporary role used during leader election when a Follower attempts to become a Leader.

2. **Leader Election**:
   - The system uses a file-based lock mechanism to manage leadership.
   - When you switch focus to an IDE or perform an action (e.g., typing), that instance attempts to acquire leadership.
   - If successful, it becomes the **Leader** and starts broadcasting its state. Previous leaders downgrade to **Followers**.

3. **State Exchange**:
   - State is serialized into JSON format and written to a shared file (`~/.one-ide/cluster/state.json`).
   - Followers watch this file for changes and apply updates in real-time.

## AI Rules Sync

The plugin monitors project-specific AI rule files. If it detects changes in rules for a supported AI tool (e.g., Cursor, Windsurf) and you are currently running a different tool (e.g., Trae), it will attempt to sync the rules to your current tool's configuration format.

Supported AI Tools:

- Cursor (`.cursorrules`, `.cursor/rules`)
- Trae (`.trae/rules`)
- Windsurf (`.windsurfrules`, `.windsurf/rules`)
- GitHub Copilot (`.github/copilot-instructions.md`)
- Claude (`.claude/rules`, `.claude.json`)
- JetBrains AI Assistant (`.aiassistant/rules`)
- Junie (`.junie`)
- Qodo (`.codiumai.toml`, `.codiumai.yaml`)
- Qoder (`.qoder`)
- Antigravity (`.antigravity/rules`)

## Installation

### JetBrains IDEs

1. Open **Settings/Preferences** > **Plugins** > **Marketplace**.
2. Search for `One-IDE`.
3. Click **Install**.

Alternatively, you can download it from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29454-one-ide).

### VS Code (and forks)

1. Open the **Extensions** view (`Ctrl+Shift+X` or `Cmd+Shift+X`).
2. Search for `One-IDE`.
3. Click **Install**.

Alternatively, you can install it from the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=tangcent.one-ide).

## Development

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
