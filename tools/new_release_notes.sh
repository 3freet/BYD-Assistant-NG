#!/usr/bin/env bash
# Starts the release notes of the next release:   tools/new_release_notes.sh <beta|stable>
#
# Writes release-notes/<tag>.md, where <tag> is the next beta of the current version (the number Gradle will give it) or the stable
# release of it, and prints the path. The file is a template: a comment that says what belongs in the notes, with the commits since
# the previous release (and for a stable release the notes of its betas) to work from, and an empty bullet. Fill it in by hand, show
# it to the maintainer, delete the comment, then commit and push: the release workflows publish the file as it is.
# tools/check_release_notes.sh refuses the file while the template is still in it.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/release_common.sh"

kind=${1:-}
cd "$(release_root)"
version=$(app_version)
case $kind in
  beta) tag=$(next_beta_tag) ;;
  stable) tag="v${version}" ;;
  *) echo "usage: new_release_notes.sh <beta|stable>" >&2; exit 2 ;;
esac
file="release-notes/${tag}.md"

if tag_exists "$tag"; then echo "$tag is released already: raise appVersionName in app/build.gradle.kts first" >&2; exit 1; fi
if [ -e "$file" ]; then echo "$file exists already: edit it" >&2; exit 1; fi

previous=$(previous_release_tag "$kind" HEAD)
range=HEAD
if [ -n "$previous" ]; then range="${previous}..HEAD"; fi
mkdir -p release-notes
{
  echo "<!--"
  echo "Release notes of $tag, to be shown to the maintainer before they are pushed."
  echo "The lines below are the commits since ${previous:-the start}, which is what the build wrote on its own before there were"
  echo "notes files. Keep them, reword them, or drop the ones of little weight (small fixes, docs). Short and specific, one line for"
  echo "each change, so that each reads well alone in the app under What's new."
  echo "Delete this whole comment when you are happy with the lines: tools/check_release_notes.sh refuses the file while it is here."
  if [ "$kind" = stable ]; then
    for notes in $(ls release-notes/v"${version}"-beta.*.md 2>/dev/null | sort -t. -k4,4n); do
      name=${notes##*/}
      echo
      echo "The notes of ${name%.md}, to work from:"
      sed 's/^/  /' "$notes"
    done
  fi
  echo "-->"
  git log --no-merges --pretty='- %s' "$range" | sed -E 's/^- beta: ?/- /'
} > "$file"
echo "$file"
