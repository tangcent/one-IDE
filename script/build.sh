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

# --- Helper: Build Functions ---
build_vscode() {
  echo "Building VS Code extension..."
  (
    cd "$(dirname "$0")/../vscode-extension"
    rm -rf dist
    npm install
    npm run compile
    echo "Packaging VS Code extension..."
    npx @vscode/vsce package --no-dependencies
  )
}

build_jetbrains() {
  echo "Building JetBrains plugin..."
  (
    cd "$(dirname "$0")/../jetbrains-plugin"
    if [ -f "./gradlew" ] && [ -f "./gradle/wrapper/gradle-wrapper.jar" ]; then
      chmod +x ./gradlew
      ./gradlew clean buildPlugin
    else
      echo "Gradle wrapper not found or incomplete. Using system gradle..."
      gradle clean buildPlugin
    fi
  )
}

# --- Main Logic ---
if [ "$TARGET" == "vscode" ] || [ "$TARGET" == "all" ]; then
  build_vscode
fi

if [ "$TARGET" == "jetbrains" ] || [ "$TARGET" == "all" ]; then
  build_jetbrains
fi

# --- Move Artifacts to build ---
BUILD_DIR="$(dirname "$0")/../build"
mkdir -p "$BUILD_DIR"
echo "Moving artifacts to $BUILD_DIR..."

if [ "$TARGET" == "vscode" ] || [ "$TARGET" == "all" ]; then
    cp "$(dirname "$0")/../vscode-extension"/*.vsix "$BUILD_DIR/" 2>/dev/null || echo "No VSIX found"
fi

if [ "$TARGET" == "jetbrains" ] || [ "$TARGET" == "all" ]; then
    cp "$(dirname "$0")/../jetbrains-plugin/build/distributions"/*.zip "$BUILD_DIR/" 2>/dev/null || echo "No JetBrains ZIP found"
fi

echo "Build complete. Artifacts in $BUILD_DIR"
