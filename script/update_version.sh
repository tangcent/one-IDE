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

# --- Helper: Select Version ---
select_version() {
    # Try to find current version from vscode package.json as a baseline
    CURRENT_VERSION=$(node -p "require('$(dirname "$0")/../vscode-extension/package.json').version")
    echo "Current VS Code version: $CURRENT_VERSION"

    IFS='.' read -r -a VERSION_PARTS <<< "$CURRENT_VERSION"
    MAJOR="${VERSION_PARTS[0]}"
    MINOR="${VERSION_PARTS[1]}"
    PATCH="${VERSION_PARTS[2]}"

    # Clean
    MAJOR=$(echo "$MAJOR" | grep -oE '^[0-9]+')
    MINOR=$(echo "$MINOR" | grep -oE '^[0-9]+')
    PATCH=$(echo "$PATCH" | grep -oE '^[0-9]+')

    NEXT_PATCH="$MAJOR.$MINOR.$((PATCH + 1))"
    NEXT_MINOR="$MAJOR.$((MINOR + 1)).0"
    NEXT_MAJOR="$((MAJOR + 1)).0.0"

    echo ""
    echo "Select a new version to release:"
    echo "1) Patch ($NEXT_PATCH)"
    echo "2) Minor ($NEXT_MINOR)"
    echo "3) Major ($NEXT_MAJOR)"
    echo "4) Custom"
    echo "5) Cancel"

    read -p "Enter your choice [1-5]: " VCHOICE
    
    case $VCHOICE in
        1) NEW_VERSION="$NEXT_PATCH" ;;
        2) NEW_VERSION="$NEXT_MINOR" ;;
        3) NEW_VERSION="$NEXT_MAJOR" ;;
        4) 
            read -p "Enter custom version: " NEW_VERSION
            if [ -z "$NEW_VERSION" ]; then
                echo "Version cannot be empty."
                exit 1
            fi
            ;;
        5) echo "Cancelled."; exit 0 ;;
        *) echo "Invalid choice."; exit 1 ;;
    esac
    
    echo "Selected version: $NEW_VERSION"
}

TARGET=$1
NEW_VERSION=$2

if [ -z "$TARGET" ]; then
    select_target
fi

if [ -z "$NEW_VERSION" ]; then
    select_version
fi

sed_i() {
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "$@"
    else
        sed -i "$@"
    fi
}

update_vscode() {
  echo "Updating VS Code version to $NEW_VERSION..."
  (
    cd "$(dirname "$0")/../vscode-extension"
    npm version "$NEW_VERSION" --no-git-tag-version --allow-same-version
  )
}

update_jetbrains() {
  echo "Updating JetBrains version to $NEW_VERSION..."
  GRADLE_FILE="$(dirname "$0")/../jetbrains-plugin/build.gradle.kts"
  # Pattern: version = "1.0.0" -> version = "NEW_VERSION"
  sed_i "s/version = \".*\"/version = \"$NEW_VERSION\"/" "$GRADLE_FILE"
}

if [ "$TARGET" == "vscode" ] || [ "$TARGET" == "all" ]; then
  update_vscode
fi

if [ "$TARGET" == "jetbrains" ] || [ "$TARGET" == "all" ]; then
  update_jetbrains
fi
