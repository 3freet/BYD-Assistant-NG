#!/usr/bin/env bash
# Tests of the release tools against a throwaway repository:   tools/test_release_tools.sh
# (release_common.sh, check_release_notes.sh, compose_release_body.sh, new_release_notes.sh, plan_release.sh). No network, no Android.
set -u
tools=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
checks=0
failures=0

pass() { checks=$((checks + 1)); }
fail() { checks=$((checks + 1)); failures=$((failures + 1)); printf 'FAIL: %s\n' "$*"; }
expect() { # <what> <expected> <actual>
  if [ "$2" = "$3" ]; then pass; else fail "$1: expected [$2], got [$3]"; fi
}
expect_contains() { # <what> <text> <needle>
  case $2 in *"$3"*) pass ;; *) fail "$1: [$3] not found in [$2]" ;; esac
}

# ── The repository the tools run in ─────────────────────────────────────────────────────────────

repo="$work/repo"
mkdir -p "$repo/app" "$repo/tools" "$repo/release-notes"
for name in release_common check_release_notes compose_release_body new_release_notes plan_release; do
  cp "$tools/$name.sh" "$repo/tools/$name.sh"
  chmod +x "$repo/tools/$name.sh"
done
cd "$repo" || exit 2
git init -q .
git checkout -q -b main 2>/dev/null || true
git config user.name test
git config user.email test@example.invalid
git config commit.gpgsign false
git config tag.gpgsign false
printf 'val appVersionName = "1.2.0"\n' > app/build.gradle.kts

commit() { git add -A && git commit -q --allow-empty -m "$1"; }
good_notes() { printf '%s\n' '- **A feature.** It does something, in plain words (Settings → App → App language).' > "$1"; }

commit "start"
git tag v1.1.0
git tag v1.1.0-beta.10
commit "first change"
git tag v1.2.0-beta.1
commit "beta: second change"
git tag v1.2.0-beta.2

# ── Which number the next beta gets (the same as Gradle's) ──────────────────────────────────────

. tools/release_common.sh
expect "the version is read from the Gradle file" 1.2.0 "$(app_version)"
expect "the next beta follows the newest" v1.2.0-beta.3 "$(next_beta_tag)"
git tag v1.2.0-beta.10
expect "betas are compared as numbers" v1.2.0-beta.11 "$(next_beta_tag)"
git tag v1.2.0-beta.x
expect "a tag with no number is ignored" v1.2.0-beta.11 "$(next_beta_tag)"
git tag -d v1.2.0-beta.10 v1.2.0-beta.x > /dev/null
expect "the betas of another version do not count" "" "$(latest_beta_number 1.3.0)"
expect "the first beta of a version is 1" v1.3.0-beta.1 "$(printf 'val appVersionName = "1.3.0"\n' > app/build.gradle.kts; next_beta_tag)"
printf 'val appVersionName = "1.2.0"\n' > app/build.gradle.kts

# ── The checks on a notes file ──────────────────────────────────────────────────────────────────

check() { tools/check_release_notes.sh "$@" > "$work/check.out" 2>&1; }

rm -f release-notes/*
good_notes release-notes/v1.2.0-beta.3.md
if check release-notes/v1.2.0-beta.3.md; then pass; else fail "a good file is accepted: $(cat "$work/check.out")"; fi

rejected() { # <what> <content> [file name]
  local name=${3:-v1.2.0-beta.3.md}
  printf '%s\n' "$2" > "release-notes/$name"
  if check "release-notes/$name"; then fail "$1: it was accepted"; else pass; fi
}
rejected "a comment left in from the template" '<!-- fill me in -->
- **A feature.** Text.'
rejected "HTML" '- **A feature.** Text<br>more'
rejected "a table" '| a | b |'
rejected "a code block" '```
code
```'
rejected "an image" '- ![x](https://example.invalid/x.png)'
rejected "an empty bullet" '- '
rejected "an unfinished marker" '- **A feature.** TODO say more'
rejected "the Full Changelog line, which the build adds" '- **A feature.** Text.

**Full Changelog**: https://example.invalid'
rejected "a network address" '- **A feature.** Open 192.168.0.20 in the browser.'
rejected "an e-mail address" '- **A feature.** Write to someone@example.invalid.'
rejected "a path on someone's computer" '- **A feature.** See /Users/someone/notes.'
rejected "a file name that is not a tag" '- **A feature.** Text.' notes.md
rejected "an empty file" '' v1.2.0-beta.3.md
awk 'BEGIN { for (i = 0; i < 300; i++) print "- **A feature.** Forty bytes of plain text, more or less." }' > release-notes/v1.2.0-beta.3.md
if check release-notes/v1.2.0-beta.3.md; then fail "a file too long for the app was accepted"; else pass; fi
printf '%s\n' '- **A feature.** Thanks to @someone for the report.' > release-notes/v1.2.0-beta.3.md
if check release-notes/v1.2.0-beta.3.md; then pass; else fail "a warning must not fail the check: $(cat "$work/check.out")"; fi
expect_contains "an @mention is warned about" "$(cat "$work/check.out")" "warning"
printf '%s\n' '- **A feature.** Version 5.0.1 and 34.1.3 are not addresses.' > release-notes/v1.2.0-beta.3.md
if check release-notes/v1.2.0-beta.3.md; then pass; else fail "version numbers are not addresses: $(cat "$work/check.out")"; fi
awk 'BEGIN { printf "- **A feature.** "; for (i = 0; i < 100; i++) printf "plain words "; print "" }' > release-notes/v1.2.0-beta.3.md
if check release-notes/v1.2.0-beta.3.md; then pass; else fail "a long line must only warn: $(cat "$work/check.out")"; fi
expect_contains "a very long line is warned about (on this machine's grep and awk too)" "$(cat "$work/check.out")" "very long line"

# ── The body of the release page ────────────────────────────────────────────────────────────────

rm -f release-notes/*
good_notes release-notes/v1.2.0-beta.2.md
good_notes release-notes/v1.2.0-beta.3.md
body=$(tools/compose_release_body.sh beta o/r v1.2.0-beta.2)
expect_contains "the notes are in the body" "$body" "A feature."
expect_contains "a release that exists compares with the one before it" "$body" "compare/v1.2.0-beta.1...v1.2.0-beta.2"
body=$(tools/compose_release_body.sh beta o/r v1.2.0-beta.3)
expect_contains "a new beta compares with the latest release" "$body" "compare/v1.2.0-beta.2...v1.2.0-beta.3"
body=$(tools/compose_release_body.sh stable o/r v1.2.0 release-notes/v1.2.0-beta.3.md)
expect_contains "a stable release compares with the latest stable one" "$body" "compare/v1.1.0...v1.2.0"
expect "the link is the last line" "**Full Changelog**: https://github.com/o/r/compare/v1.1.0...v1.2.0" "$(printf '%s\n' "$body" | tail -n 1)"
if tools/compose_release_body.sh beta o/r v1.2.0-beta.9 > /dev/null 2>&1; then fail "a missing notes file was accepted"; else pass; fi

# ── What a beta run does ────────────────────────────────────────────────────────────────────────

plan() { # <kind> [VAR=value ...]  ->  status, mode, tag, notes_out, log
  local kind=$1
  shift
  : > "$work/out"
  env -i PATH="$PATH" HOME="${HOME:-/}" GITHUB_OUTPUT="$work/out" "$@" tools/plan_release.sh "$kind" > "$work/log" 2>&1
  status=$?
  mode=$(sed -n 's/^mode=//p' "$work/out")
  tag=$(sed -n 's/^tag=//p' "$work/out")
  notes_out=$(sed -n 's/^notes=//p' "$work/out")
  log=$(cat "$work/log")
}

rm -f release-notes/*
commit "tidy"
before=$(git rev-parse HEAD)
good_notes release-notes/v1.2.0-beta.3.md
commit "notes for beta 3"
notes_commit=$(git rev-parse HEAD)

plan beta EVENT_NAME=push BEFORE="$before" AFTER="$notes_commit" REF=refs/heads/main
expect "a push that adds the next beta's notes: status" 0 "$status"
expect "  it builds and publishes" build "$mode"
expect "  the tag" v1.2.0-beta.3 "$tag"
expect "  the notes" release-notes/v1.2.0-beta.3.md "$notes_out"
plan beta EVENT_NAME=push BEFORE="$before" AFTER="$notes_commit" REF=refs/heads/wip/try
expect "the same push on another branch is a dry run" dry-run "$mode"
plan beta EVENT_NAME=push BEFORE="$before" AFTER="$notes_commit" REF=refs/heads/main DRY_RUN=true
expect "a dry run is a dry run" dry-run "$mode"
plan beta EVENT_NAME=push BEFORE=0000000000000000000000000000000000000000 AFTER="$notes_commit" REF=refs/heads/main
expect "a push to a new branch counts the last commit" build "$mode"

before=$(git rev-parse HEAD)
good_notes release-notes/v1.2.0-beta.1.md
commit "archive the notes of beta 1"
plan beta EVENT_NAME=push BEFORE="$before" AFTER="$(git rev-parse HEAD)" REF=refs/heads/main
expect "notes added for a release that exists are kept, not built: status" 0 "$status"
expect "  mode" skip "$mode"

before=$(git rev-parse HEAD)
good_notes release-notes/v1.2.0-beta.9.md
commit "notes with the wrong number"
plan beta EVENT_NAME=push BEFORE="$before" AFTER="$(git rev-parse HEAD)" REF=refs/heads/main
expect "notes for a number that is not the next fail: status" 1 "$status"
expect_contains "  and say which is the next" "$log" "the next beta is v1.2.0-beta.3"
git rm -q release-notes/v1.2.0-beta.9.md
commit "drop them"

before=$(git rev-parse HEAD)
printf '%s\n' '- **A feature.** It does something else, in plain words.' > release-notes/v1.2.0-beta.3.md
commit "fix a typo in the notes"
plan beta EVENT_NAME=push BEFORE="$before" AFTER="$(git rev-parse HEAD)" REF=refs/heads/main
expect "a changed notes file is not a request for a build" skip "$mode"

before=$(git rev-parse HEAD)
commit "a change with no notes"
plan beta EVENT_NAME=push BEFORE="$before" AFTER="$(git rev-parse HEAD)" REF=refs/heads/main
expect "a push without notes builds nothing" skip "$mode"

plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main
expect "a run by hand builds the next beta from its notes" build "$mode"
expect "  the tag" v1.2.0-beta.3 "$tag"
plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main DRY_RUN=true
expect "a run by hand can be a dry run" dry-run "$mode"
mv release-notes/v1.2.0-beta.3.md "$work/held.md"
plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main
expect "a run without the notes file fails: status" 1 "$status"
expect_contains "  and says how to write it" "$log" "new_release_notes.sh beta"
printf '%s\n' '<!-- not finished -->' '- **A feature.** Text.' > release-notes/v1.2.0-beta.3.md
plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main
expect "a run with a notes file that fails the check fails: status" 1 "$status"
mv "$work/held.md" release-notes/v1.2.0-beta.3.md

plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main NOTES_ONLY=true TAG_INPUT=v1.2.0-beta.1
expect "notes only for a release that has its file: mode" notes-only "$mode"
expect "  the tag" v1.2.0-beta.1 "$tag"
plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main NOTES_ONLY=true TAG_INPUT=v1.2.0-beta.1 DRY_RUN=true
expect "notes only as a dry run changes nothing" skip "$mode"
plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main NOTES_ONLY=true TAG_INPUT=v1.1.0
expect "notes only for a stable release is refused here" 1 "$status"
plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main NOTES_ONLY=true TAG_INPUT=v1.2.0-beta.7
expect "notes only for a release that does not exist fails" 1 "$status"
plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main NOTES_ONLY=true
expect "notes only without a tag fails" 1 "$status"
plan beta EVENT_NAME=workflow_dispatch REF=refs/heads/main NOTES_ONLY=true TAG_INPUT=v1.2.0-beta.2
expect "notes only for a release with no file fails" 1 "$status"

# ── What a stable run does ──────────────────────────────────────────────────────────────────────

rm -f release-notes/*
commit "tidy again"
plan stable EVENT_NAME=push REF=refs/heads/stable
expect "a release without its notes fails" 1 "$status"
expect_contains "  and says how to write them" "$log" "new_release_notes.sh stable"

good_notes release-notes/v1.2.0.md
commit "notes for the release"
plan stable EVENT_NAME=push REF=refs/heads/stable
expect "a release of tested code: status" 0 "$status"
expect "  mode" release "$mode"
expect "  tag" v1.2.0 "$tag"
plan stable EVENT_NAME=workflow_dispatch REF=refs/heads/main
expect "a run from another branch is a dry run" dry-run "$mode"
plan stable EVENT_NAME=workflow_dispatch REF=refs/heads/stable DRY_RUN=true
expect "a dry run is a dry run" dry-run "$mode"

mkdir -p app/src
echo changed > app/src/changed.txt
commit "change the app after the beta"
plan stable EVENT_NAME=push REF=refs/heads/stable
expect "a release of code that changed since the newest beta fails: status" 1 "$status"
expect_contains "  and names the file" "$log" "app/src/changed.txt"
plan stable EVENT_NAME=workflow_dispatch REF=refs/heads/stable ALLOW_CHANGES=true
expect "  unless it is allowed by hand" release "$mode"

printf 'val appVersionName = "1.3.0"\n' > app/build.gradle.kts
good_notes release-notes/v1.3.0.md
commit "version 1.3.0"
plan stable EVENT_NAME=push REF=refs/heads/stable
expect "a release with no beta of its version fails" 1 "$status"
plan stable EVENT_NAME=workflow_dispatch REF=refs/heads/stable ALLOW_CHANGES=true
expect "  unless it is allowed by hand" release "$mode"
printf 'val appVersionName = "1.2.0"\n' > app/build.gradle.kts
commit "back to 1.2.0"

git tag v1.2.0
plan stable EVENT_NAME=push REF=refs/heads/stable
expect "a release that exists already fails" 1 "$status"
git tag -d v1.2.0 > /dev/null

# ── Starting the notes of the next release ──────────────────────────────────────────────────────

rm -f release-notes/*
commit "beta: a change worth a line"
started=release-notes/v1.2.0-beta.3.md
if tools/new_release_notes.sh beta > "$work/new.out" 2>&1; then pass; else fail "the starter failed: $(cat "$work/new.out")"; fi
expect "the starter prints the path of the next beta's file" "$started" "$(tail -n 1 "$work/new.out")"
without_comment=$(sed '/^<!--$/,/^-->$/d' "$started")
expect_contains "  the commits since the previous release are real bullets, as the build used to write them" "$without_comment" "- a change worth a line"
case $without_comment in *"beta:"*) fail "the beta: prefix of a subject was kept" ;; *) pass ;; esac
expect_contains "  a comment asks to be deleted once the lines are approved" "$(cat "$started")" "Delete this whole comment"
if check "$started"; then fail "the template was accepted as notes"; else pass; fi
printf '%s\n' "$without_comment" > "$started"
if check "$started"; then pass; else fail "the lines the starter wrote are not accepted once the comment is gone: $(cat "$work/check.out")"; fi
if tools/new_release_notes.sh beta > /dev/null 2>&1; then fail "the starter overwrote a file"; else pass; fi

rm -f release-notes/*
good_notes release-notes/v1.2.0-beta.1.md
if tools/new_release_notes.sh stable > "$work/new.out" 2>&1; then pass; else fail "the starter failed for a stable release: $(cat "$work/new.out")"; fi
expect "the starter prints the path of the stable file" release-notes/v1.2.0.md "$(tail -n 1 "$work/new.out")"
expect_contains "  the notes of the betas are in the comment, to work from" "$(cat release-notes/v1.2.0.md)" "The notes of v1.2.0-beta.1, to work from:"
expect_contains "  with what they said" "$(sed -n '/^<!--$/,/^-->$/p' release-notes/v1.2.0.md)" "A feature."
expect_contains "  and the lines are the commits since the previous stable release" "$(sed '/^<!--$/,/^-->$/d' release-notes/v1.2.0.md)" "- first change"

printf '\n%d checks, %d failed\n' "$checks" "$failures"
[ "$failures" -eq 0 ]
