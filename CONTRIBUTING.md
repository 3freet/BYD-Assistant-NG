# Contributing to BYD Assistant NG

Thank you for helping. The most valuable contributions are **confirmations from other vehicles**, new
**verified controls**, **translations**, and bug reports with a log. Please read
[docs/byd-internals](docs/byd-internals/README.md) before touching anything that talks to the car.

## Ground rules

- **Safety first.** Anything that affects driving — engine, gearbox, motor, brakes, driver assistance, radar,
  the security system, power — stays unreachable. A command in one of those domains is rejected when the
  registry loads, and a unit test guards it. Do not add an exception.
- **Your own vehicle only.** A control must come from what *you* observed on *your* head unit, using the
  read-only method in [research-method.md](docs/byd-internals/research-method.md). Do not paste ids, tables or
  data taken from other apps, leaked documents or someone else's proprietary files.
- **Parked, and read-back.** Test controls only while parked. A control is only "verified" when the car's state
  was read back and matched; say how you restored what you changed.
- **No personal data.** No API keys, transcripts, logs with what was said, locations, VINs, device serials or
  network addresses in code, issues or screenshots.

## Branches and releases

Two branches:

| Branch | Purpose | What happens |
| --- | --- | --- |
| `main` | Development. Open pull requests against it | Every push runs unit tests and lint ([ci.yml](.github/workflows/ci.yml)) |
| `stable` | What has been released | Pushing to it runs the tests and lint, builds and signs the app, and publishes a GitHub **release** `v<version>` ([stable.yml](.github/workflows/stable.yml)) |

**Pre-releases** are built from `main` on request ([beta.yml](.github/workflows/beta.yml)): commit with a message
that begins with `beta:`, or press *Run workflow* for "Build Beta" in the Actions tab. It tests, builds, signs and
publishes `v<version>-beta.N`, numbered automatically.

**To release:** test a beta on a vehicle, then fast-forward `stable` to the commit you tested:

```bash
git push origin main:stable
```

A version can be published once: raise `appVersionName` in `app/build.gradle.kts` first (`major.minor.patch`, each
0-99), or the workflow stops at once with a message saying so. The Android `versionCode` is computed from the name, so
it always rises with the version and a beta (`1.2.0-beta.3`) always sorts below the release it leads up to
(`1.2.0`). Raise the version **right after** publishing a stable release, before the next beta: the beta workflow
refuses to build a beta of a version that is already released.

### How the app updates itself

The app checks the GitHub releases of the repository it was built for (`-Passistant.updateRepo`) and offers the newest
build on the **channel** the user follows (Settings → Advanced): *Stable* sees only releases, *Beta* sees betas and
releases alike, whichever is newer. It never offers a build that is not newer than the installed one, so switching
channel cannot downgrade.

- The release notes shown under *What's new* are the commit subjects since the previous release, written by
  [`tools/release_notes.sh`](tools/release_notes.sh) — so write commit subjects a user could read.
- An update is downloaded only from the repository's own release assets, checked against the size and SHA-256 GitHub
  recorded for the file, and checked to be the same package signed by the same key as the installed app, before it is
  handed to the installer.
- It installs over the same loopback ADB connection the vehicle helper uses (`cat apk | pm install -r -S <size>`),
  then starts the app again; without ADB it opens the system installer.
- A local `assembleDebug` build installs as `com.bydassistantng.dev` and does not update itself. To try the whole flow
  without publishing anything, use the debug-only `DebugUpdateReceiver` (see its header comment).

### Signing secrets (maintainers)

The `beta` and `stable` workflows refuse to run without these repository secrets, so a release can never be signed
with the throwaway debug key:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the keystore, base64-encoded (`base64 -i release.jks`) |
| `KEY_ALIAS` | the key's alias |
| `KEY_PASSWORD` | the password (used for both the key and the keystore) |

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore release.jks -alias bydassistant \
        -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore somewhere safe and **out of the repository** (`*.jks` is ignored). Losing it means existing
installs can no longer be updated.

## Building and testing

JDK 21 and the Android SDK are required.

```bash
./gradlew testDebugUnitTest lintRelease    # what CI runs
./gradlew assembleDebug                    # installs as com.bydassistantng.dev
./gradlew assembleRelease                  # minified (R8) — also test this one on a vehicle
```

Pass `-Passistant.updateRepo=owner/repo` to enable the in-app update check and the project link in *About*.

## Adding a vehicle control

1. **Find it** with the [research method](docs/byd-internals/research-method.md): operate the native control while
   recording, identify the state ids and values, find the matching `…_SET` control (or the A/C property).
2. **Add an entry** to `app/src/main/resources/vehicle_commands.json`:

   | Field | Meaning |
   | --- | --- |
   | `id`, `domain` | e.g. `seat.driver.heating`, `SETTING`. A blocked domain is dropped at load time |
   | `displayName` | A description for the model (long is fine) |
   | `label`, `labelAr` | Short names for the status banner, English and Arabic |
   | `deviceType`, `featureId` | The device and the **control** id (generic route), or the A/C `propertyId` and `area` |
   | `stateFeatureId` | The **state** id to read back. Required for anything you can read |
   | `parameter` | `fixedEnum` (`options`) or `range` (`min`, `max`, `unit`) in the units a user speaks |
   | `valueOffset` | Added to the value before it is sent, when the car's signal differs from the screen |
   | `requires` | A state that must hold first, else the command is refused without touching the car |
   | `overrides` | A different control/value for one particular value (massage "off") |
   | `stateTolerance`, `gradual` | For slow movers such as windows |

3. **Add a test** in `VehicleCommandRegistryTest` for anything that is not obvious (ids, values, offsets).
4. **Regenerate the reference:** `python3 tools/gen_command_docs.py`.
5. **Verify on a vehicle** and describe it in the pull request: model, model year, software version, what you
   observed, and how you restored the state.

## Translations

All text is in `app/src/main/res/values/strings.xml`. Add `values-<code>/strings.xml` with the **same keys and
placeholders** (a test checks this), and add the command labels to the registry. Brand names such as the app
name are not translated.

## Code style

Kotlin official style. Comments explain *why* — what was measured, what failed — not what the next line does.
New behaviour needs a unit test where it can have one; the audio and protocol logic is written so that it can.
Keep commits small and the messages plain.

## Reporting problems

Open an issue with: the vehicle model and year, the head-unit software version, the app version (*About*), what
you did and what happened. Do **not** attach the app log without reading it first: it can contain what you said
and where you asked to navigate. Security problems: see [SECURITY.md](SECURITY.md).

## Licence

The project is licensed under the GNU General Public License, version 3 ([LICENSE](LICENSE)). By contributing you agree
that your contribution is licensed the same way.
