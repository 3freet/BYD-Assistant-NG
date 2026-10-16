# Security

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private reporting: on the repository's *Security* tab choose
*Report a vulnerability*. Include what you found, the version, and how to reproduce it. This is a volunteer
project, so there is no guaranteed response time, but reports are taken seriously.

## What this app can do, and why

An app that operates a car deserves a plain account of its reach.

**It runs a helper process as the ADB shell user.** The vehicle permissions are signature-level, so the app cannot
hold them. It therefore connects to the head unit's own ADB daemon over the loopback interface (with the `dadb`
library, using a key pair it creates in its private storage) and starts a small helper from its own APK. Over that
connection the app runs a short, fixed list of commands: `pm grant <this app> android.permission.WRITE_SECURE_SETTINGS`;
the helper (`app_process … VehicleShellHelper --serve`); when you ask it to close an app, `pidof` and `am force-stop`
for that one app, and only if you installed it (never the car's own apps or itself); `pm disable-user` and `pm enable`
for BYD's own voice assistant, and only when you press its button in Settings; and, to start a track in a music or
video app, reading that app's screen and tapping it. Installing an update is described below. The helper accepts
one `<commandId> <value>` line at a time and **re-validates it** against the command registry: unknown or blocked commands and out-of-range values are
refused in the helper itself, whatever the app sent.

What this means for you:

- The app's key is an authorised ADB key. Anything that could use that key could run **any** shell command as the
  shell user. Install builds only from sources you trust, and keep ADB debugging in mind as a property of the
  head unit: another app on the unit that connects to the same loopback daemon may be able to do the same
  without this app.
- The helper process stays alive for a few minutes after the last command and then exits.

**It is an accessibility service, for one key.** It asks to filter key events and nothing else: no window
content, no events, no gestures (see `res/xml/wheel_key_service_config.xml`). It only reacts to the configured
steering-wheel key and passes every other key through. It also draws a small non-interactive status banner.

**It holds `WRITE_SECURE_SETTINGS`** (granted over ADB, never at install) so that it can switch its own
accessibility service on; the unit has no settings screen for that.

**It does not offer the systems that affect driving — as an app-level safeguard, not an operating-system one.**
Engine, gearbox, motor, driver-assistance, radar, security and power domains are excluded in three places: the
registry refuses to load an entry in them (so the model is never offered such a function), the helper process
refuses them again, and the dispatch layer refuses a third time. Be clear about what that is: the helper runs as
the shell user, which the vehicle services accept for **every** device, so these checks are this app's own
safeguards. A modified build, or any other program using the same ADB access, is not bound by them.

**There are three deliberate, narrow exceptions**, all experimental and all **off until the user switches them on**
(Settings → Advanced → Experimental features, each behind a confirmation):

- *The exterior speaker switch*: one feature id in the driver-assistance group, the values `0` and `1`, written by a
  separate helper process that switches it off again by itself if the app stops sending a heartbeat, the
  connection closes, or after 60 seconds. ([details](docs/byd-internals/exterior-speaker.md))
- *The drive-mode request*: one feature id in the setting device, with the mode numbers the car's own screen sends
  (Normal, Eco, Sport, Snow, Sand, Mud, Mountain, Rock, Auto and exit; never wade, warp, custom or traction), only
  below 80 km/h, followed by a read-back of what the car did. ([details](docs/byd-internals/drive-modes.md))
- *The instrument cluster*: one feature id in the instrument device (the map type, `1` for the normal gauges and `4`
  for an app on the cluster), written by a separate helper command, together with switching BYD's navigation app on
  and off for the main user and starting and stopping apps on the cluster's own display. The app is shown in the
  middle of the cluster, where BYD's map is shown, and the speed and the warning lights stay visible; the feature has
  **no park check** and anything shown there is in the driver's view, so it is for a parked car. ([details](docs/byd-internals/cluster-screen.md))

None of them goes through the command registry, so the model is never offered them as vehicle commands. Each is used
only in a build that contains the experimental car features (beta and debug builds; not stable releases yet) **and**
only when the car's framework gives the feature name the id it was confirmed with on the development vehicle — on any
other car, or on a phone, they are absent (the cluster feature also needs the cluster's own display and BYD's
navigation app to be there). All three were confirmed on a single vehicle; another model may number the same names
differently, which is what that check is for.

## Data

- **Voice** is streamed to Google's Gemini API over TLS and is not stored by the app. With *Web search* on, your
  questions also go to Google Search. Google's own terms apply to that data.
- **Memory** (what you asked the assistant to remember, and the last few exchanges for ten minutes) is stored in the
  app's private storage on the device, not backed up, and can be read, deleted or cleared in Settings. It is sent
  to Gemini as part of the instructions of a conversation so the assistant can use it. Each part can be switched off.
- **The API key** is encrypted with an Android Keystore AES key before it is stored; the key never leaves the
  Keystore. Android backup is switched off for the app (`allowBackup="false"`), so neither the encrypted key nor
  the settings are copied to a cloud backup or to a new device. Cleartext network traffic is permitted only to
  the loopback addresses.
- **Logs** stay on the device (`app_log.txt`, `crash_log.txt`) and leave it only when you press *Share*. The
  application log currently records what the assistant heard and the places you asked to navigate to, so read a
  log before you send it to anyone.
- **No analytics, telemetry or advertising** of any kind.
- **Updates** are only checked when the build was told which GitHub repository publishes them
  (`assistant.updateRepo`), and only ever downloaded from that repository's release assets. Before an update is
  installed the app checks the file against the size and SHA-256 GitHub recorded, and that it is the same package signed
  with the same key as the installed app; Android enforces the signature again when it installs. Nothing is installed
  without a tap, and never during a conversation. The installation uses the same local ADB connection as the vehicle
  helper (see above), so it can replace the app without a confirmation screen.

## Responsible use

Vehicle control is used entirely at your own responsibility; see the disclaimer in the app's *About* screen. Use
it while it is safe to do so. The experimental features (exterior speaker, drive modes, instrument cluster) are exactly that —
experimental, tried on one car, and switched on only by you.
