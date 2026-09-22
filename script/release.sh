#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

RELEASE_BRANCH="main"

# --- Helper: Sync to the latest release branch ---
# A release must be cut from the tip of main: update_version.sh rewrites version
# files and release_note.sh derives notes from PREV_TAG..HEAD, so a stale local
# checkout would silently produce a wrong changelog.
sync_release_branch() {
    # Switching branches with uncommitted tracked changes either fails or
    # silently carries them into the release, so refuse up front.
    if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
        echo "Error: working tree has uncommitted changes. Commit or stash them first." >&2
        git status --short --untracked-files=no >&2
        exit 1
    fi

    # --tags is required: release_note.sh resolves PREV_TAG via git describe.
    echo "Fetching from origin (including tags)..."
    git fetch --tags --prune origin

    if [ "$(git rev-parse --abbrev-ref HEAD)" = "$RELEASE_BRANCH" ]; then
        echo "Already on '$RELEASE_BRANCH'."
    else
        echo "Switching to '$RELEASE_BRANCH'..."
        if git rev-parse --verify --quiet "refs/heads/$RELEASE_BRANCH" >/dev/null; then
            git checkout "$RELEASE_BRANCH"
        else
            git checkout -b "$RELEASE_BRANCH" "origin/$RELEASE_BRANCH"
        fi
    fi

    echo "Fast-forwarding '$RELEASE_BRANCH' to origin/$RELEASE_BRANCH..."
    if ! git pull --ff-only origin "$RELEASE_BRANCH"; then
        echo "Error: '$RELEASE_BRANCH' cannot be fast-forwarded to origin/$RELEASE_BRANCH." >&2
        echo "Resolve the divergence manually, then re-run this script." >&2
        exit 1
    fi

    echo "Release base: $RELEASE_BRANCH @ $(git rev-parse --short HEAD)"
}

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

# 0. Sync with the latest release branch
sync_release_branch

TARGET=$1

if [ -z "$TARGET" ]; then
    select_target
fi

# 1. Update Version
# This will ask for the version interactively if not provided
"$SCRIPT_DIR/update_version.sh" "$TARGET"

# 2. Generate Release Notes
echo "Generating release notes..."
"$SCRIPT_DIR/release_note.sh" "$TARGET"

echo
echo "Release preparation complete."
echo "Please review changes, commit, and push."
