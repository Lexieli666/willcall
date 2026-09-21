#!/usr/bin/env bash
# Mirror the working build to the checkout on the Windows drive.
#
# The repository's home is a path under /mnt/d, which is a Windows NTFS volume mounted into WSL.
# Measured on this machine, creating 300 small files there takes 472 ms against 9 ms on the WSL
# ext4 filesystem - about fifty times slower, a penalty a Gradle build and an `npm ci` would pay
# on every file. So the build lives on ext4 and is mirrored across, .git included, at each phase
# boundary.
#
# Ignored paths are excluded: mirroring node_modules over a 9p mount would take longer than the
# build that produced it, and none of it is tracked anyway.
#
# The exclusions have to be anchored. A bare `dist/` also matched load/sse/dist, which *is*
# tracked - the SSE generator ships compiled so it can run without a build step - so every mirror
# left three deleted files in the Windows checkout's working tree. An exclude list that deletes
# committed files is worse than no exclude list.
set -euo pipefail

SOURCE="${WILLCALL_BUILD_DIR:-$HOME/willcall}"
TARGET="${1:?usage: mirror-to-windows.sh <target directory>}"

[ -d "$SOURCE/.git" ] || { printf 'no git repository at %s\n' "$SOURCE" >&2; exit 1; }
mkdir -p "$TARGET"

rsync -a --delete \
  --exclude '.gradle/' \
  --exclude 'build/' \
  --exclude 'node_modules/' \
  --exclude '/web/dist/' \
  --exclude 'coverage/' \
  --exclude '.lighthouseci/' \
  --exclude 'playwright-report/' \
  --exclude 'test-results/' \
  --exclude '.terraform/' \
  --exclude '*.tsbuildinfo' \
  "$SOURCE/" "$TARGET/"

printf 'mirrored %s -> %s\n' "$SOURCE" "$TARGET"
printf 'HEAD: %s\n' "$(git -C "$TARGET" log --oneline -1)"
printf 'working tree: %s\n' "$(git -C "$TARGET" status --porcelain | wc -l) uncommitted path(s)"
