# Project Rules

- **Cross-Plugin Feature Parity**: When updating the code of any plugin (e.g., JetBrains plugin), always consider if the same update needs to be applied to other plugins (e.g., VS Code extension) to maintain feature parity and consistency.
- **Service-Based Architecture**: Maintain a consistent service-oriented architecture across both platforms. Core logic should reside in services with identical names and responsibilities (e.g., `ClusterService`, `RuleService`, `ConfigService`).
- **Shared State & File System**: Use the same file-based coordination mechanism. Shared data must be stored in `~/.one-ide/` with identical file structures and JSON formats.
- **Testing Requirements**: Core services and logic must have corresponding unit tests in both plugins to ensure consistent behavior across platforms.
