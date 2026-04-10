# Change Log

All notable changes to the "one-ide" extension will be documented in this file.

## [1.1.6] - 2026-04-10
- fix: correct path boundary checking in StateHelper

## [1.1.5] - 2026-03-12
- fix: prevent interference with AI coding tools during file edits

## [1.1.4] - 2026-03-01
- fix: settings not persisting when toggling off in IDEA settings (#6)
- feat: Complete Kiro support - add steering rules, update gitignore and README (#5)
- fix: leader should step down when window loses focus (#4)
- feat: Add Kiro rules support (#2)
- refactor(cluster): decouple role actions and sync orchestration across plugins (#1)

## [1.1.3] - 2026-01-06
- feat(sync): add pause/resume functionality to cluster service

## [1.1.2] - 2025-12-27
- feat(rules): add preferred extension support for rule files
- feat: add support for text selection synchronization

## [1.1.1] - 2025-12-26
- feat: notify user on plugin version mismatch across IDEs
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
