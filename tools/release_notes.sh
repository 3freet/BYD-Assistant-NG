#!/usr/bin/env bash
# Prints the release notes for the build about to be published: the subject of every commit since the previous
# release (a beta is compared with the latest release of either kind, a stable release with the latest stable one),
# newest first. The app shows these lines under "What's new" when it offers the update.
#
#   tools/release_notes.sh <stable|beta> <owner/repo> <new tag>
set -euo pipefail

kind="${1:?usage: release_notes.sh <stable|beta> <owner/repo> <new tag>}"
repo="${2:?missing repository}"
tag="${3:?missing tag}"

if [ "$kind" = stable ]; then
  previous=$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' --exclude '*-*' HEAD 2>/dev/null || true)
else
  previous=$(git describe --tags --abbrev=0 --match 'v[0-9]*' HEAD 2>/dev/null || true)
fi

range="HEAD"
[ -n "$previous" ] && range="${previous}..HEAD"

# "beta:" at the start of a subject only starts the build; it is not part of the change.
git log --no-merges --max-count=40 --pretty='- %s' "$range" | sed -E 's/^- beta: ?/- /'

if [ -n "$previous" ]; then
  printf '\n**Full Changelog**: https://github.com/%s/compare/%s...%s\n' "$repo" "$previous" "$tag"
fi
