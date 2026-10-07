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
connection the app runs only two kinds of command: `pm grant <this app> android.permission.WRITE_SECURE_SETTINGS`,
and the helper (`app_process … VehicleShellHelper --serve`). The helper accepts one `<commandId> <value>` line at a
time and **re-validates it** against the command registry: unknown or blocked commands and out-of-range values are
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

## Data

- **Voice** is streamed to Google's Gemini API over TLS and is not stored by the app. With *Web search* on, your
  questions also go to Google Search. Google's own terms apply to that data.
- **The API key** is encrypted with an Android Keystore AES key before it is stored; the key never leaves the
  Keystore. Android backup is switched off for the app (`allowBackup="false"`), so neither the encrypted key nor
  the settings are copied to a cloud backup or to a new device. Cleartext network traffic is permitted only to
  the loopback addresses.
- **Logs** stay on the device (`app_log.txt`, `crash_log.txt`) and leave it only when you press *Share*. The
  application log currently records what the assistant heard and the places you asked to navigate to, so read a
  log before you send it to anyone.
- **No analytics, telemetry or advertising** of any kind.
- **Updates** are only checked when the build was told which GitHub repository publishes them
  (`assistant.updateRepo`); Android will only install an update signed with the same key as the installed app.

## Responsible use

Vehicle control is experimental and is used entirely at your own responsibility; see the disclaimer in the app's
*About* screen. Use it while parked.
