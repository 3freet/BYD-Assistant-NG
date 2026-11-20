#!/usr/bin/env bash
# Puts together the files a release keeps on its page:   tools/stage_release_assets.sh <beta|stable> <tag>
#
# The release workflows run it after the APK is built and before the release is created. It writes release-assets/ with
#
#   <project>-<version>.apk              the APK. Gradle names it after its version alone, so the name is the same for every build of that
#                                        version and says nothing of the day it was built
#   <project>-<version>-mapping.txt.gz   the R8 mapping of that build, which turns an obfuscated crash trace of it back into readable code
#                                        (gzip: the text is 43 MB, the file about 3 MB)
#
# and the workflow puts both on the release. A workflow artifact is deleted after 90 days, an asset of a release stays for as long as the
# release does: that is why the mapping is kept on the release and not only as an artifact.
#
# It stops, saying why, when there is not exactly one APK, when the APK is not named after the tag (the name comes from outputFileName in
# app/build.gradle.kts), or when the mapping is missing: a release the next person cannot install, or cannot debug, is not published.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/release_common.sh"

kind=${1:-}
tag=${2:-}
case $kind in
  beta) variant=beta ;;
  stable) variant=release ;;
  *) echo "usage: stage_release_assets.sh <beta|stable> <tag>" >&2; exit 2 ;;
esac
if [ -z "$tag" ]; then echo "usage: stage_release_assets.sh <beta|stable> <tag>" >&2; exit 2; fi
cd "$(release_root)"

say() {
  printf '%s\n' "$*"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n\n' "$*" >> "$GITHUB_STEP_SUMMARY"; fi
}

die() {
  if [ "${GITHUB_ACTIONS:-}" = true ]; then printf '::error::%s\n' "$*"; else printf 'error: %s\n' "$*" >&2; fi
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '**%s**\n\n' "$*" >> "$GITHUB_STEP_SUMMARY"; fi
  exit 1
}

project=$(project_name)
[ -n "$project" ] || die "could not read rootProject.name from settings.gradle.kts"
version=${tag#v}
apk_name="${project}-${version}.apk"
mapping_name="${project}-${version}-mapping.txt.gz"
apk_dir="app/build/outputs/apk/${variant}"
mapping="app/build/outputs/mapping/${variant}/mapping.txt"

built=""
count=0
for file in "$apk_dir"/*.apk; do
  [ -e "$file" ] || continue
  built=$file
  count=$((count + 1))
done
[ "$count" -ge 1 ] || die "there is no APK in $apk_dir to put on the release $tag"
[ "$count" -eq 1 ] || die "there are $count APKs in $apk_dir, and the release $tag takes one"
[ "$(basename "$built")" = "$apk_name" ] ||
  die "the APK is named $(basename "$built"), but the release $tag keeps its APK as $apk_name: the name comes from outputFileName in app/build.gradle.kts"
[ -s "$mapping" ] || die "the R8 mapping $mapping is missing or empty: without it a crash trace of this build cannot be read later"

rm -rf release-assets
mkdir release-assets
cp "$built" "release-assets/$apk_name"
gzip -9 -n -c "$mapping" > "release-assets/$mapping_name"

# sha256sum on the runners, shasum on a Mac.
digest() { if command -v sha256sum > /dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

list="Files of the release $tag, kept on its page:"
for file in release-assets/*; do
  list="$list
- $(basename "$file") ($(wc -c < "$file" | tr -d ' ') bytes, sha256 $(digest "$file"))"
done
say "$list"
