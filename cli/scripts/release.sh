#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT" || exit 1

CURRENT_VERSION=$(node -p "require('./package.json').version")
echo "Current version: $CURRENT_VERSION"

IFS='.' read -r -a VERSION_PARTS <<< "$CURRENT_VERSION"
MAJOR=$(echo "${VERSION_PARTS[0]}" | grep -oE '^[0-9]+')
MINOR=$(echo "${VERSION_PARTS[1]}" | grep -oE '^[0-9]+')
PATCH=$(echo "${VERSION_PARTS[2]}" | grep -oE '^[0-9]+')

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

read -p "Enter your choice [1-5]: " CHOICE

NEW_VERSION=""
case $CHOICE in
    1) NEW_VERSION="$NEXT_PATCH" ;;
    2) NEW_VERSION="$NEXT_MINOR" ;;
    3) NEW_VERSION="$NEXT_MAJOR" ;;
    4)
        read -p "Enter custom version: " NEW_VERSION
        [ -z "$NEW_VERSION" ] && echo "Version cannot be empty." && exit 1
        ;;
    5) echo "Release cancelled." && exit 0 ;;
    *) echo "Invalid choice." && exit 1 ;;
esac

echo ""
echo "You are about to release version: $NEW_VERSION"
read -p "Are you sure? (y/N) " CONFIRM
[[ ! "$CONFIRM" =~ ^([yY][eE][sS]|[yY])$ ]] && echo "Cancelled." && exit 0

echo ""
echo "Updating version to $NEW_VERSION..."
npm version "$NEW_VERSION" --git-tag-version=false || exit 1

echo "Building project..."
npm run build || { echo "Build failed. Aborting release."; exit 1; }

echo ""
echo "Publishing to npm..."
npm publish --access public || { echo "Publish failed."; exit 1; }

echo ""
echo "Successfully published version $NEW_VERSION!"

read -p "Do you want to create a git tag and push? (y/N) " GIT_TAG
if [[ "$GIT_TAG" =~ ^([yY][eE][sS]|[yY])$ ]]; then
    git add package.json package-lock.json
    git commit -m "chore(cli): release $NEW_VERSION"
    git tag "cli/v$NEW_VERSION"
    git push && git push --tags
fi
