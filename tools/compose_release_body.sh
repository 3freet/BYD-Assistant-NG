#!/usr/bin/env bash
# Prints the body of a release page: the hand-written notes, then the link to everything that changed.
#
#   tools/compose_release_body.sh <beta|stable> <owner/repo> <tag> [notes file]     (default notes file: release-notes/<tag>.md)
#
# The link compares the release with the one before it: for a beta the latest release of either kind, for a stable release the
# latest stable one. For a tag that exists already (notes put on it again) "before" is counted from that tag, not from the branch.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/release_common.sh"

kind=${1:?usage: compose_release_body.sh <beta|stable> <owner/repo> <tag> [notes file]}
repo=${2:?missing repository (owner/repo)}
tag=${3:?missing tag}
cd "$(release_root)"
notes=${4:-release-notes/${tag}.md}
[ -f "$notes" ] || { echo "there is no release notes file $notes" >&2; exit 1; }

if tag_exists "$tag"; then rev="${tag}^"; else rev=HEAD; fi
previous=$(previous_release_tag "$kind" "$rev")

# The notes end with exactly one blank line, whatever the file ends with.
printf '%s\n\n' "$(cat "$notes")"
if [ -n "$previous" ]; then
  printf '**Full Changelog**: https://github.com/%s/compare/%s...%s\n' "$repo" "$previous" "$tag"
fi
