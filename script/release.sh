#!/bin/bash
set -e

# --- Helper: Select Target ---
select_target() {
    echo "Select target:"
    echo "1) All"
    echo "2) VS Code"
    echo "3) JetBrains"
    read -p "Enter choice [1-3]: " CHOICE
    case $CHOICE in
        1) TARGET="all" ;;
        2) TARGET="vscode" ;;
        3) TARGET="jetbrains" ;;
        *) echo "Invalid choice"; exit 1 ;;
    esac
}

TARGET=$1

if [ -z "$TARGET" ]; then
    select_target
fi

# 1. Update Version
# This will ask for the version interactively if not provided
"$(dirname "$0")/update_version.sh" "$TARGET"

# 2. Generate Release Notes
echo "Generating release notes..."
"$(dirname "$0")/release_note.sh" "$TARGET"

echo "Release preparation complete."
echo "Please review changes, commit, and push."
