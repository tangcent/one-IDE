#!/bin/bash
set -e

# --- Configuration ---
JB_FILTER="\(jetbrains\)|\[jetbrains\]|jetbrains:|idea:|intellij:"
VS_FILTER="\(vscode\)|\[vscode\]|vscode:"

# --- Helper: Select Target ---
select_target() {
    echo "Select target:" >&2
    echo "1) All" >&2
    echo "2) VS Code" >&2
    echo "3) JetBrains" >&2
    read -p "Enter choice [1-3]: " CHOICE
    case $CHOICE in
        1) TARGET="all" ;;
        2) TARGET="vscode" ;;
        3) TARGET="jetbrains" ;;
        *) echo "Invalid choice" >&2; exit 1 ;;
    esac
}

TARGET=$1
PREV_TAG=$2

if [ -z "$TARGET" ]; then
    select_target
fi

if [ -z "$PREV_TAG" ]; then
  # Try to get the latest tag. If no tags, default to empty (all history) or first commit
  if git describe --tags --abbrev=0 >/dev/null 2>&1; then
      PREV_TAG=$(git describe --tags --abbrev=0)
  else
      echo "No tags found. Generating notes for all commits." >&2
      PREV_TAG=""
  fi
fi

RANGE="HEAD"
if [ -n "$PREV_TAG" ]; then
    RANGE="$PREV_TAG..HEAD"
fi

echo "Generating release notes for range: $RANGE" >&2

# --- Function: Get Logs ---
# usage: get_logs <exclude_pattern> <format>
get_logs() {
    local EXCLUDE=$1
    local FMT=$2
    # Get logs, filter out the excluded pattern
    git log --pretty=format:"$FMT" "$RANGE" | grep -v -iE "$EXCLUDE" || true
}

# --- JetBrains Logic ---
if [ "$TARGET" == "jetbrains" ] || [ "$TARGET" == "all" ]; then
    CHANGES_FILE="$(dirname "$0")/../jetbrains-plugin/parts/pluginChanges.html"
    echo "Writing JetBrains change notes to $CHANGES_FILE" >&2
    
    # Exclude VS Code specific commits
    LOGS=$(get_logs "$VS_FILTER" "<li>%s</li>")
    MD_LOGS=$(get_logs "$VS_FILTER" "- %s")
    
    if [ -n "$LOGS" ]; then
        echo "<ul>" > "$CHANGES_FILE"
        echo "$LOGS" >> "$CHANGES_FILE"
        echo "</ul>" >> "$CHANGES_FILE"
        
        # Output for Release Body
        echo "## JetBrains Plugin Changes"
        echo "$MD_LOGS"
        echo ""
    else
        echo "<p>No significant changes.</p>" > "$CHANGES_FILE"
    fi
fi

# --- VS Code Logic ---
if [ "$TARGET" == "vscode" ] || [ "$TARGET" == "all" ]; then
    CHANGELOG_FILE="$(dirname "$0")/../vscode-extension/CHANGELOG.md"
    echo "Updating VS Code changelog at $CHANGELOG_FILE" >&2
    
    # Exclude JetBrains specific commits
    LOGS=$(get_logs "$JB_FILTER" "- %s")
    
    if [ -n "$LOGS" ]; then
        # Get version from package.json
        VERSION=$(grep '"version":' "$(dirname "$0")/../vscode-extension/package.json" | head -n 1 | sed -E 's/.*"version": "(.*)",/\1/')
        DATE=$(date +%Y-%m-%d)
        
        TEMP_FILE=$(mktemp)
        
        # Prepare new section
        echo "## [$VERSION] - $DATE" > "$TEMP_FILE"
        echo "$LOGS" >> "$TEMP_FILE"
        echo "" >> "$TEMP_FILE"
        
        if [ -f "$CHANGELOG_FILE" ]; then
            # Find the line number of the first version header (starts with ## )
            FIRST_VERSION_LINE=$(grep -n "^## " "$CHANGELOG_FILE" | head -n 1 | cut -d: -f1)
            
            if [ -n "$FIRST_VERSION_LINE" ]; then
                # Split file: Header part (before first version) and Rest
                head -n $((FIRST_VERSION_LINE - 1)) "$CHANGELOG_FILE" > "$CHANGELOG_FILE.tmp"
                cat "$TEMP_FILE" >> "$CHANGELOG_FILE.tmp"
                tail -n +$FIRST_VERSION_LINE "$CHANGELOG_FILE" >> "$CHANGELOG_FILE.tmp"
                mv "$CHANGELOG_FILE.tmp" "$CHANGELOG_FILE"
            else
                # No existing versions, just append to end (presumably after header)
                cat "$TEMP_FILE" >> "$CHANGELOG_FILE"
            fi
        else
            # Create new file with header
            echo "# Change Log" > "$CHANGELOG_FILE"
            echo "" >> "$CHANGELOG_FILE"
            cat "$TEMP_FILE" >> "$CHANGELOG_FILE"
        fi
        
        rm "$TEMP_FILE"
        echo "VS Code changelog updated." >&2
        
        # Output for Release Body
        echo "## VS Code Extension Changes"
        echo "$LOGS"
        echo ""
    else
        echo "No significant changes for VS Code." >&2
    fi
fi
