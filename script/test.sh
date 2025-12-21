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

test_vscode() {
  echo "Testing VS Code extension..."
  (
    cd "$(dirname "$0")/../vscode-extension"
    npm install
    npm test
  )
}

test_jetbrains() {
  echo "Testing JetBrains plugin..."
  (
    cd "$(dirname "$0")/../jetbrains-plugin"
    if [ -f "./gradlew" ]; then
      chmod +x ./gradlew
      ./gradlew test
    else
      gradle test
    fi
  )
}

TARGET=$1
if [ -z "$TARGET" ]; then
    select_target
fi

if [ "$TARGET" == "vscode" ] || [ "$TARGET" == "all" ]; then
  test_vscode
fi

if [ "$TARGET" == "jetbrains" ] || [ "$TARGET" == "all" ]; then
  test_jetbrains
fi
