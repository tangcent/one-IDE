# Change Log

All notable changes to the "one-ide" extension will be documented in this file.

## [1.1.1] - 2025-12-26
- feat: notify user on plugin version mismatch across IDEs
- chore(logger): add MetaDataLogger for simplified logging with context
- refactor(cluster): remove node heartbeat functionality
- refactor(state): simplify state structure

## [1.1.0] - 2025-12-25
- feat:Add debouncer to prevent rapid consecutive state applications from other IDEs
- refactor(state): simplify state structure

## [1.0.6] - 2025-12-24
- fix(cluster): handle window focus during election to prevent race condition

## [1.0.5] - 2025-12-24
- fix: make file path comparisons case insensitive

## [1.0.4] - 2025-12-23
- refactor(cluster): Optimize the synchronization strategy

## [1.0.3] - 2025-12-22
- feat: Significant improve performance

## [1.0.2] - 2025-12-21
- feat: add Antigravity support

## [1.0.0] - 2025-12-20
- feat: add initial implementation of One-IDE sync service
- Initial commit

## [Unreleased]
- Initial release
