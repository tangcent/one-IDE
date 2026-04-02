#!/bin/bash
set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT" || exit 1

publish_npm() {
    echo "🚀 Publishing to npmjs.com..."
    npm publish --access public
}

publish_github() {
    echo "🚀 Publishing to GitHub Packages..."

    if ! npm whoami --registry=https://npm.pkg.github.com >/dev/null 2>&1; then
        echo "⚠️  You are not logged in to GitHub Packages."
        echo "📦 You need a GitHub Personal Access Token (classic) with:"
        echo "   ✅ write:packages"
        echo "   ✅ read:packages"
        echo ""
        echo "🔗 Create one here: https://github.com/settings/tokens/new?scopes=write:packages,read:packages&description=one-ide-cli%20Publish"
        echo ""
        read -p "Would you like to log in now? [y/N] " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            npm login --registry=https://npm.pkg.github.com || { echo "❌ Login failed."; exit 1; }
        else
            echo "❌ Authentication required. Run 'npm login --registry=https://npm.pkg.github.com' and try again."
            exit 1
        fi
    fi

    cp package.json package.json.bak

    node -e "
    const fs = require('fs');
    const pkg = require('./package.json');
    pkg.name = '@tangcent/one-ide-cli';
    fs.writeFileSync('package.json', JSON.stringify(pkg, null, 2));
    "

    cleanup() {
        rm -f .npmrc
        mv package.json.bak package.json
    }
    trap cleanup EXIT

    npm publish --registry=https://npm.pkg.github.com

    cleanup
    trap - EXIT
}

TARGET=$1

if [ -z "$TARGET" ]; then
    echo "Select registry to publish to:"
    echo "1) All (GitHub & NPM)"
    echo "2) NPM only (npmjs.com)"
    echo "3) GitHub only (npm.pkg.github.com)"
    read -p "Enter choice [1-3]: " choice
    case $choice in
        1) TARGET="all" ;;
        2) TARGET="npm" ;;
        3) TARGET="github" ;;
        *) echo "❌ Invalid choice" && exit 1 ;;
    esac
fi

echo "👉 Selected target: $TARGET"

case $TARGET in
    npm)     publish_npm ;;
    github)  publish_github ;;
    all)
        echo "📦 Publishing to ALL registries..."
        publish_github && echo "✅ GitHub publish success." || echo "⚠️  GitHub publish failed. Continuing..."
        publish_npm && echo "✅ npm publish success." || { echo "❌ npm publish failed."; exit 1; }
        ;;
    *)
        echo "❌ Invalid option: $TARGET"
        echo "Usage: ./scripts/publish.sh [github|npm|all]"
        exit 1
        ;;
esac

echo "🎉 Done!"
