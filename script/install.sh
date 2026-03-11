#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/build"

# --- Build ---
echo "==> Building VS Code extension..."
"$SCRIPT_DIR/build.sh" vscode

# --- Find VSIX ---
VSIX_FILE=$(ls -t "$BUILD_DIR"/*.vsix 2>/dev/null | head -1)
if [ -z "$VSIX_FILE" ]; then
    echo "Error: No .vsix file found in $BUILD_DIR"
    exit 1
fi
echo "==> Found VSIX: $(basename "$VSIX_FILE")"

# --- Detect & Install ---
# VS Code and known forks that support --install-extension
CLI_NAMES=("code" "codium" "cursor" "trae" "windsurf" "kiro" "antigravity" "qoder")

INSTALLED=0
FAILED=0

for cli in "${CLI_NAMES[@]}"; do
    if command -v "$cli" &>/dev/null; then
        echo "==> Installing into $cli..."
        if "$cli" --install-extension "$VSIX_FILE" --force 2>&1; then
            echo "    ✓ $cli: installed successfully"
            ((INSTALLED++))
        else
            echo "    ✗ $cli: installation failed"
            ((FAILED++))
        fi
    else
        echo "    - $cli: not found, skipping"
    fi
done

# --- Summary ---
echo ""
if [ $INSTALLED -eq 0 ] && [ $FAILED -eq 0 ]; then
    echo "No VS Code forks detected. Supported CLIs: ${CLI_NAMES[*]}"
    echo "Make sure the CLI command is in your PATH (e.g., install 'code' command from VS Code Command Palette)."
    exit 1
fi

echo "Done. Installed: $INSTALLED, Failed: $FAILED"
