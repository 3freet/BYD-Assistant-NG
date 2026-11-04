#!/usr/bin/env bash
# Decides what a run of a release workflow does, and checks that it can, before anything is built:
#
#   tools/plan_release.sh <beta|stable>
#
# It reads, from the environment (the workflow sets them):
#   EVENT_NAME     push or workflow_dispatch
#   BEFORE, AFTER  the commits a push went from and to (for the files the push added)
#   REF            the ref being built; only main publishes a beta and only stable publishes a release, anywhere else it is a dry run
#   DRY_RUN        true: build and show the notes, publish nothing
#   NOTES_ONLY     true (beta only): put the notes file on an existing release again, build nothing
#   TAG_INPUT      the release NOTES_ONLY is about
#   ALLOW_CHANGES  true (stable only): release although the app changed since the newest beta, or without a beta
# and writes mode=, tag= and notes= to $GITHUB_OUTPUT (to stdout when that is not set). The mode is
#   build or release   build and publish          dry-run      build and show the notes, publish nothing
#   notes-only         put the notes on the release   skip     nothing to do (the push did not ask for a build)
# It says in plain words why, and exits 1 with an error when what was asked cannot be done, so that a run fails in seconds and not
# after a ten minute build.
set -u
here=$(cd "$(dirname "$0")" && pwd)
. "$here/release_common.sh"
root=$(release_root) || exit 2
cd "$root" || exit 2

kind=${1:-}
case $kind in
  beta) publish_ref=refs/heads/main ;;
  stable) publish_ref=refs/heads/stable ;;
  *) echo "usage: plan_release.sh <beta|stable>" >&2; exit 2 ;;
esac

event=${EVENT_NAME:-workflow_dispatch}
ref=${REF:-}
dry=${DRY_RUN:-false}
notes_only=${NOTES_ONLY:-false}
allow=${ALLOW_CHANGES:-false}

emit() { printf '%s=%s\n' "$1" "$2" >> "${GITHUB_OUTPUT:-/dev/stdout}"; }

say() {
  printf '%s\n' "$*"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n\n' "$*" >> "$GITHUB_STEP_SUMMARY"; fi
}

die() {
  if [ "${GITHUB_ACTIONS:-}" = true ]; then printf '::error::%s\n' "$*"; else printf 'error: %s\n' "$*" >&2; fi
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '**%s**\n\n' "$*" >> "$GITHUB_STEP_SUMMARY"; fi
  exit 1
}

# The files a push added under a path pattern (a push to a new branch, or one whose "before" is gone, counts its last commit).
added_files() { # <before> <after> <pathspec>
  local before=$1 after=$2 spec=$3
  if [ -n "$before" ] && ! printf '%s' "$before" | grep -Eq '^0+$' && git cat-file -e "${before}^{commit}" 2>/dev/null; then
    git diff --name-only --diff-filter=A "$before" "$after" -- "$spec"
  else
    git show --name-only --diff-filter=A --format= "$after" -- "$spec"
  fi
}

check_notes() { # <file>
  [ -f "$1" ] || die "the release notes $1 are missing: tools/new_release_notes.sh $kind writes the file to fill in, show to the maintainer, and push"
  "$here/check_release_notes.sh" "$1" || die "$1 has problems (listed above)"
}

# The mode of a build: only the publishing branch publishes, and a dry run never does.
finish() { # <build|release> <tag> <notes>
  local mode=$1
  if [ "$dry" = true ]; then
    mode=dry-run
    say "Dry run: the build is made and the notes are shown, nothing is published."
  elif [ "$ref" != "$publish_ref" ]; then
    mode=dry-run
    say "This is ${ref:-an unknown ref}, not ${publish_ref#refs/heads/}: the build is made and the notes are shown, nothing is published."
  fi
  emit mode "$mode"
  emit tag "$2"
  emit notes "$3"
}

plan_beta() {
  local next tag notes added file candidate=""
  next=$(next_beta_tag)

  if [ "$notes_only" = true ]; then
    tag=${TAG_INPUT:-}
    [ -n "$tag" ] || die "notes_only needs the tag of the release to put the notes on (the input tag, for example the tag of the newest beta)"
    tag_exists "$tag" || die "there is no release $tag to put the notes on"
    notes="release-notes/${tag}.md"
    check_notes "$notes"
    say "Notes only: $notes goes on the release $tag again; nothing is built."
    emit mode notes-only
    emit tag "$tag"
    emit notes "$notes"
    return 0
  fi

  if [ "$event" = push ]; then
    added=$(added_files "${BEFORE:-}" "${AFTER:-HEAD}" 'release-notes/v*-beta.*.md')
    for file in $added; do
      tag=$(basename "$file" .md)
      if [ "$tag" = "$next" ]; then
        candidate=$tag
      elif tag_exists "$tag"; then
        say "$file holds the notes of $tag, which is released already: kept, nothing to build."
      else
        die "$file is for $tag but the next beta is $next: the number must be the next one (tools/new_release_notes.sh beta writes release-notes/$next.md)"
      fi
    done
    if [ -z "$candidate" ]; then
      say "No notes for the next beta ($next) were added by this push: nothing to build."
      emit mode skip
      emit tag ""
      emit notes ""
      return 0
    fi
    tag=$candidate
  else
    tag=$next
  fi

  notes="release-notes/${tag}.md"
  check_notes "$notes"
  say "The beta $tag will be built, and $notes is its release notes."
  finish build "$tag" "$notes"
}

plan_stable() {
  local version tag notes number beta_tag changed list
  version=$(app_version)
  [ -n "$version" ] || die "could not read appVersionName from app/build.gradle.kts"
  tag="v${version}"
  notes="release-notes/${tag}.md"

  if tag_exists "$tag"; then die "the release $tag exists already: raise appVersionName in app/build.gradle.kts first"; fi
  check_notes "$notes"

  # What is released is what was tested: the app must not have changed since the newest beta of this version.
  number=$(latest_beta_number "$version")
  if [ -z "$number" ]; then
    if [ "$allow" != true ]; then
      die "there is no beta of $version: a release is the build of a beta that was tested on a car (run the workflow by hand with allow_changes to release anyway)"
    fi
    say "Warning: there is no beta of $version; released anyway because it was allowed by hand."
  else
    beta_tag="v${version}-beta.${number}"
    changed=$(git diff --name-only "$beta_tag" HEAD -- app build.gradle.kts settings.gradle.kts gradle.properties gradle)
    if [ -n "$changed" ]; then
      list=$(printf '%s\n' "$changed" | head -n 8 | tr '\n' ' ')
      if [ "$allow" != true ]; then
        die "the app changed since $beta_tag, the newest beta, so what would be released was not tested: $list- build and test another beta first (or run the workflow by hand with allow_changes)"
      fi
      say "Warning: the app changed since $beta_tag ($list); released anyway because it was allowed by hand."
    else
      say "The app is the same as in $beta_tag, the newest beta: what is released was tested."
    fi
  fi

  say "The release $tag will be built, and $notes is its release notes."
  finish release "$tag" "$notes"
}

"plan_$kind"
