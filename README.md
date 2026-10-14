# BYD Assistant NG

**English** · [العربية](README.ar.md)

A hands-free voice assistant for **BYD DiLink head units**, built on Google's **Gemini Live** API. Press the
microphone button on the steering wheel, talk naturally in Arabic or English, and it can answer, navigate,
open apps, control the media player, operate the car's comfort controls and — on request — speak through the
exterior speaker or change the drive mode.

> **Unofficial.** This is an independent, community project. It is not affiliated with, endorsed by or
> supported by BYD or Google. "BYD", "DiLink" and "Gemini" are trademarks of their respective owners.
> You use it entirely at your own responsibility — see [Safety](#safety).

| English | العربية (right-to-left) |
| --- | --- |
| ![Home, English](docs/images/home-en.png) | ![Home, Arabic](docs/images/home-ar.png) |
| ![Settings, English](docs/images/settings-en.png) | ![Settings, Arabic](docs/images/settings-ar.png) |

## What it does

- **Natural conversation** with Gemini Live: speak, it answers aloud, and you can talk over it to interrupt.
- **Steering-wheel button** starts and ends a conversation (an accessibility service that watches that one key).
- **Status banner** on screen shows whether the mic is live, the assistant is thinking, or it is speaking.
- **Tap sound**: every tap in the app ticks with the head unit's own touch sound, like the rest of the car's screens.
- **Navigation** ("take me to …"), **open any installed app** by name, and **play / pause / next / previous**.
- **Play something in an app**: "play <song> on Spotify", "a video about <topic> on YouTube", or "put on something
  moody" and it picks one. It uses Android's standard play-from-search request, so it works with any installed
  app that supports it; it cannot pick from an app's home screen.
- **Web search** through Google Search (optional; needs a paid Gemini plan — see below).
- **Vehicle control** (on by default; switch it off in Settings): A/C (power, temperature, fan, air direction,
  air source, ventilation, compressor, auto, defrost), windows (open/close or any position), sunroof, sunshade,
  trunk, volume, seat heating / ventilation / massage, steering-wheel heating, the cabin light and the fridge. The
  full list, with ids and what is verified, is in [vehicle-commands.md](docs/byd-internals/vehicle-commands.md).
- **Outside temperature** ("how hot is it outside?"), read from the instrument cluster; same switch as vehicle control.
- **Tyre pressure** ("how are my tyres?"): reads each wheel's pressure, the car's own low/high and leak warnings
  and the temperature, and answers in the unit you ask for. Read-only, but it uses the same switch as vehicle
  control. See [tyres.md](docs/byd-internals/tyres.md).
- **Memory**: *short-term* memory carries the last few exchanges into the next conversation for ten minutes, so a
  follow-up such as "do the same for the passenger" works; *long-term* memory keeps what you ask it to
  remember ("remember that I like the cabin at 21 degrees") or add by hand, and you can read, delete or clear
  the list in Settings. Each can be switched off. It is stored on the device only (see [Privacy](#privacy)).
- **Wait after you stop speaking**: a slider (0.4–1.5 s, 0.8 s by default) for how long the assistant waits in
  silence before it decides you have finished — shorter answers sooner, longer lets you pause mid-sentence.
- **English and Arabic**: the whole app is translated, including full right-to-left layout. *App language*
  (screens and messages) and *Assistant language* (what it speaks) are separate settings, as are the
  assistant's voice and, for Arabic, its dialect.

### Experimental features (beta builds, off by default)

These reach further into the car than the comfort controls, so they are **off until you switch them on** under
*Settings → Advanced settings → Experimental features*, each with its own confirmation. They are only offered on
a car whose framework names the feature the way it was confirmed on the development vehicle, and only in beta
builds for now. Everything about how they work is in [docs/byd-internals](docs/byd-internals/README.md).

- **Exterior speaker**: "tell the person outside I'm coming" makes the assistant's reply come out of the car's
  exterior speaker (nobody in the cabin hears it), and "play a doorbell outside" plays one of nine short effects
  (doorbell, chime, bell, beeps, alarm, horn, whistle, bird, cat). Three volume levels (low, medium, high), set in
  Advanced settings or asked for by voice ("say it loudly"). How and why:
  [exterior-speaker.md](docs/byd-internals/exterior-speaker.md).
- **Drive modes**: "switch to sand mode" (also snow, mud, mountain, rock, auto, eco, sport, and "exit" to leave an
  off-road mode). The assistant asks the car the way the car's own screen does, then reports the mode the car says
  it is really in — the car decides, and it is refused above 80 km/h. Wade, warp, custom and traction modes are not
  offered. It combines with other requests: "sand mode and play some music". How and why:
  [drive-modes.md](docs/byd-internals/drive-modes.md).

## Requirements

- A BYD DiLink head unit running an Android-13-based system (arm64). Vehicle control was developed and tested
  on a single vehicle and firmware; other models may behave differently.
- A **Gemini API key** from [Google AI Studio](https://aistudio.google.com/).
- An internet connection for the head unit (for example a phone hotspot).
- **ADB debugging** switched on in the head unit. It is used locally, over the loopback interface, to switch the
  steering-wheel service on and to run the helper process that talks to the vehicle services (the vehicle
  permissions are signature-level, so an ordinary app process cannot hold them). If the unit asks to authorise
  debugging, allow it. See [SECURITY.md](SECURITY.md) for what that implies.

## Installing

1. Download the APK from the project's **Releases** page and install it on the head unit (a file manager, or
   `adb install`). Allow installs from unknown sources if asked.
2. Open the app and follow the setup: grant the microphone, enter your Gemini API key, and turn on the
   steering-wheel button.
3. On BYD firmware, switch the app on in the head unit's **auto-start** screen (Settings → *Open auto-start
   settings*). Without it the system can close the app when the car sleeps, which turns the button off.
4. *Vehicle control* is on by default; switch it off in Settings if you only want to talk. The experimental
   features under *Advanced settings* stay off until you turn them on.

### Updates

The app looks for a newer version on GitHub about every six hours and shows a notice on the home screen (and a quiet
notification) when there is one. *Settings → Updates* shows what is new and installs it when you confirm: the app
downloads the file, checks it is this app, signed by the same key and intact, then installs it and starts again. Do it
while parked, since a conversation in progress ends. *Settings → Advanced settings* switches between the **Stable**
channel (tested releases) and the **Beta** channel (newest builds first), and turns the automatic check off.

## Privacy

- What you say is streamed to the Gemini API to produce the reply. Nothing is sent anywhere else, and there is
  no analytics or tracking.
- With **Web search** on, the questions you ask are also sent to Google Search.
- The update check asks GitHub for the project's list of releases (nothing about you or the car is sent). Turn it
  off under *Settings → Advanced settings*.
- The API key is stored encrypted on the device. Logs stay on the device until you choose to share them — and
  can contain what you said, so read one before you send it.
- **Memory** is kept on the device (not in a cloud backup). What it holds — the remembered facts and, for ten
  minutes, the last few exchanges — is sent to Gemini as part of the instructions of the next conversation, so
  the assistant can use it. Switch either memory off, or clear it, in Settings.

## Safety

Vehicle control is used entirely **at your own risk**: use it while it is safe to do so and check that the car did
what you asked. Anything that affects driving — engine, gearbox, motor, brakes, driver assistance, radar,
security and power systems — is deliberately not reachable: those commands are excluded from the command list,
never offered to the model, and refused again at dispatch time. Commands confirm their effect by reading the
car's state back, and report honestly when the car did not apply them.

There are exactly **two deliberate exceptions**, both experimental and off by default: the exterior speaker
switch and the drive-mode request. Each is a single feature id with fixed values, outside the command list, and
is used only when the car's framework names it as it was confirmed, and after you switch it on and accept the
warning. See [SECURITY.md](SECURITY.md).

## Web search

Google only allows search grounding on a **paid Gemini plan** (billing enabled on the API key's project). The
setting is off by default; on a free key Google refuses the session, and the app retries without search.

## Documentation

| | |
| --- | --- |
| [docs/byd-internals](docs/byd-internals/README.md) | How the app reaches the car's internals, the ids and values found, the research method, and what did **not** work |
| [docs/architecture.md](docs/architecture.md) | How the app is put together |
| [tools/probes](tools/probes) | The read-only probes used to explore a head unit |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Branches, releases, adding a control, translations |
| [SECURITY.md](SECURITY.md) | What the app can do, what it stores, and how to report a vulnerability |

## Building

Requires JDK 21 and the Android SDK.

```bash
./gradlew assembleDebug          # debug build (installs next to a release build as ".dev")
./gradlew testDebugUnitTest      # unit tests
./gradlew lintRelease            # lint
```

Optional build settings:

- `-Passistant.updateRepo=owner/repo` (or the `ASSISTANT_UPDATE_REPO` environment variable): the GitHub
  repository whose releases the in-app updater checks. Without it the app never checks for updates and hides
  the Updates section. (A debug build never updates itself.)
- A release signing key goes in `app/signing.properties` (not tracked). Never commit keystores or passwords.

## Branches

`main` is development; a commit starting with `beta:` (or a manual run) publishes a pre-release from it, and pushing to
`stable` publishes a release. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Project layout

| Path | What is there |
| --- | --- |
| `gemini/` | Live API client, the conversation state machine, tool wiring, failure handling |
| `audio/` | Mic and speaker I/O, end-of-speech detection, talk-over (barge-in) detection, the exterior speaker |
| `vehicle/` | Command registry (`resources/vehicle_commands.json`), safety gate, the helper process, drive modes |
| `memory/` | Short-term and long-term memory and the tools that edit it |
| `service/` | The steering-wheel accessibility service and the status banner |
| `apps/`, `media/`, `navigation/` | The open-app, media and navigation tools |
| `res/values`, `res/values-ar` | English and Arabic strings — a unit test keeps the two in step |

## License

BYD Assistant NG is free software, licensed under the **GNU General Public License, version 3** ([LICENSE](LICENSE)).
You may use, study, change and share it under those terms; changes you distribute must stay under the same licence.
It comes with no warranty — see [Safety](#safety).
