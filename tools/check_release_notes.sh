#!/usr/bin/env bash
# Checks release notes files:   tools/check_release_notes.sh [file ...]      (no file: every release-notes/v*.md)
#
# The notes of a release are release-notes/<tag>.md, written by hand, shown to the maintainer and then pushed; the release workflows
# publish them as they are. The app turns the text of a release page into plain lines, so these checks keep out what it cannot show
# (HTML, tables, code blocks, images), what does not belong on a public release page (addresses, e-mail addresses, paths on someone's
# computer) and a template that was never filled in. Exit status 1 when a file has a problem; warnings do not fail.
set -u
here=$(cd "$(dirname "$0")" && pwd)
. "$here/release_common.sh"
root=$(release_root) || exit 2
cd "$root" || exit 2

if [ $# -eq 0 ]; then
  listed=$(ls release-notes/v*.md 2>/dev/null || true)
  if [ -z "$listed" ]; then
    echo "no release notes files to check"
    exit 0
  fi
  # shellcheck disable=SC2086  # file names here never contain spaces
  set -- $listed
fi

hits=$(mktemp)
trap 'rm -f "$hits"' EXIT
problems=0

report() { # <error|warning> <file> <line> <message>
  if [ "$1" = error ]; then problems=$((problems + 1)); fi
  if [ "${GITHUB_ACTIONS:-}" = true ]; then
    printf '::%s file=%s,line=%s::%s\n' "$1" "$2" "$3" "$4"
  else
    printf '%s:%s: %s: %s\n' "$2" "$3" "$1" "$4"
  fi
}

scan() { # <error|warning> <file> <message> <grep options and pattern ...>
  local severity=$1 file=$2 message=$3 hit status
  shift 3
  grep -n "$@" "$file" > "$hits"
  status=$?
  # 1 is "nothing found"; anything above is grep failing (a pattern this grep does not accept): never skip a check without saying so.
  if [ "$status" -gt 1 ]; then
    report error "$file" 1 "the check for '$message' could not run (grep failed)"
    return
  fi
  while IFS= read -r hit; do
    report "$severity" "$file" "${hit%%:*}" "$message"
  done < "$hits"
}

for file in "$@"; do
  before=$problems
  name=${file##*/}
  if [ ! -f "$file" ]; then
    report error "$file" 1 "no such file"
    continue
  fi
  printf '%s' "$name" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-beta\.[0-9]+)?\.md$' ||
    report error "$file" 1 "the name must be the release's tag: v<major>.<minor>.<patch>.md, or v<major>.<minor>.<patch>-beta.<N>.md for a beta"
  if ! grep -q '[^[:space:]]' "$file"; then
    report error "$file" 1 "the file is empty"
    continue
  fi
  bytes=$(wc -c < "$file" | tr -d ' ')
  if [ "$bytes" -gt 5500 ]; then
    report error "$file" 1 "$bytes bytes: the app shows at most 6,000 characters of a release page, and the build adds a line at the end"
  fi

  scan error "$file" "HTML or a comment: the app shows it as text (a comment left from the template must be deleted)" \
    -E '<!--|</?(br|p|div|span|details|summary|img|a|table|tr|td|ul|li|b|i|code|pre|h[1-6]|sup|sub|kbd)([[:space:]/>]|$)'
  scan error "$file" "a table: the app shows it as text" -E '^[[:space:]]*\|'
  scan error "$file" "a code block: the app shows its fence lines as blanks" -E '^[[:space:]]*(```|~~~)'
  scan error "$file" "an image: the app cannot show it" -E '!\[[^]]*\]\('
  scan error "$file" "an empty bullet" -E '^[[:space:]]*[-*][[:space:]]*$'
  scan error "$file" "an unfinished marker (TODO, FIXME or XXX)" -w -i -E 'TODO|FIXME|XXX'
  scan error "$file" "the build adds the Full Changelog line itself" -i -F 'Full Changelog'
  scan error "$file" "a network address: none belongs on a public release page" \
    -E '(^|[^0-9.])[0-9]{1,3}(\.[0-9]{1,3}){3}([^0-9.]|$)'
  scan error "$file" "an e-mail address" -E '[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+'
  scan error "$file" "a path on someone's computer" -E '/Users/|/home/[a-z]|[A-Za-z]:\\Users'
  scan warning "$file" "an @mention notifies that person when the release is published" -E '(^|[^A-Za-z0-9_@.])@[A-Za-z0-9][A-Za-z0-9_-]*'
  scan warning "$file" "a markdown link: the app shows it as text" -E '\]\(https?://'
  # awk, not a grep pattern: BSD grep (macOS) accepts a repetition of at most 255.
  awk 'length($0) > 800 { print NR ":" }' "$file" > "$hits"
  while IFS= read -r hit; do
    report warning "$file" "${hit%%:*}" "a very long line (over 800 characters)"
  done < "$hits"

  if [ "$problems" -eq "$before" ]; then
    echo "ok: $file ($bytes bytes)"
  fi
done

[ "$problems" -eq 0 ]
