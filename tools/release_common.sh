# Shared by the release tools (new_release_notes.sh, check_release_notes.sh, compose_release_body.sh, plan_release.sh).
# Source it: it only defines functions. It works with the bash that ships with macOS (3.2) and with the runners' bash.

release_root() { git rev-parse --show-toplevel; }

# The version the next release will have, read from app/build.gradle.kts the way the workflows read it.
app_version() { sed -n 's/^val appVersionName = "\(.*\)"/\1/p' "$(release_root)/app/build.gradle.kts"; }

# The number of the newest beta of a version: the highest N among the tags v<version>-beta.N, empty when there is none. A tag whose
# suffix is not a plain number is ignored, as Gradle ignores it when it numbers the next beta (app/build.gradle.kts, betaNumber).
latest_beta_number() { git tag -l "v${1}-beta.*" | sed -n 's/^.*-beta\.\([0-9][0-9]*\)$/\1/p' | sort -n | tail -n 1; }

# The tag the next beta of the current version will get: one more than the newest beta, or 1. This is the number Gradle gives the build.
next_beta_tag() {
  local version number
  version=$(app_version)
  number=$(latest_beta_number "$version")
  echo "v${version}-beta.$((10#${number:-0} + 1))"
}

tag_exists() { git rev-parse -q --verify "refs/tags/$1" >/dev/null 2>&1; }

# The latest release before <rev>: for a beta's page the latest release of either kind, for a stable one's the latest stable release.
previous_release_tag() { # <beta|stable> <rev>
  if [ "$1" = stable ]; then
    git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' --exclude '*-*' "$2" 2>/dev/null || true
  else
    git describe --tags --abbrev=0 --match 'v[0-9]*' "$2" 2>/dev/null || true
  fi
}
