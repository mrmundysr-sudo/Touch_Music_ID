#!/usr/bin/env bash
# Puts Apple's ShazamKit AAR at app/libs/ (git-ignored) so CI builds the real engine.
# Source: the owner-supplied transfer zip committed on main. If it is absent the build still succeeds and the app
# honestly reports "SDK not linked". Nothing is printed from inside the SDK package other than file names.
set -u
DEST=app/libs/shazamkit-android-release.aar
[ -f "$DEST" ] && exit 0
ZIP=ShazamKitAndroid2.1.1-transfer.zip
git fetch --depth=1 origin main >/dev/null 2>&1 || { echo "fetch-shazamkit: could not fetch main; building without the SDK"; exit 0; }
git show "origin/main:$ZIP" > "$RUNNER_TEMP/$ZIP" 2>/dev/null || { echo "fetch-shazamkit: $ZIP not on main; building without the SDK"; exit 0; }
mkdir -p app/libs
unzip -oj "$RUNNER_TEMP/$ZIP" shazamkit-android-release.aar -d app/libs >/dev/null && echo "fetch-shazamkit: AAR placed at $DEST"
