# How the app works

A short tour for contributors. For the vehicle side, see [byd-internals](byd-internals/README.md).

## The pieces

| Package | Role |
| --- | --- |
| `service/WheelKeyService` | The accessibility service. Catches the steering-wheel button and **hosts the conversation** (so it works with any app in front). Also owns the status banner |
| `service/StatusBanner` | A non-touchable overlay pill, built from plain views (Compose would need its own lifecycle inside a service) |
| `gemini/LiveConversationController` | The conversation state machine over the Gemini Live WebSocket |
| `gemini/GeminiLiveClient` | The wire protocol only: setup, audio frames, tool calls, events |
| `gemini/ConversationShared` | The system prompt, the tool list, and the dispatcher that routes a function call |
| `audio/` | Microphone and speaker I/O, end-of-speech detection, talk-over detection, and the exterior speaker (its switch helper, the navi-stream track and the synthesised sound effects) |
| `vehicle/` | The command registry, the safety gate, the helper process, the read-only queries such as tyre pressure, and drive-mode switching (see byd-internals) |
| `memory/` | Short-term and long-term memory: the stores, the prompt text built from them, and the `remember` / `forget_memory` tools |
| `apps/`, `media/`, `navigation/` | The non-vehicle tools: open an app, media keys, navigation |
| `ui/` | The screens: home, onboarding, settings, about (Jetpack Compose). `ui/theme` is the one palette for the whole app; `ui/settings/SettingsComponents` holds the card/row/switch/dialog pieces the settings pages are built from; `ui/TapSound` gives Compose taps the system tap sound |
| `util/AppLanguage` | In-app language and layout direction |

## One conversation

The conversation runs a gate on the microphone with three positions:

- **OPEN** — the user's turn. Mic audio goes to the server. A local end-of-speech detector (a level threshold
  that follows the cabin noise) notices the pause early so the app can play the "got it" tone.
- **SILENT** — the user has finished. Zeros are sent instead of audio so the server's own voice detection hears
  the silence it needs. (Stopping the stream, or sending `audioStreamEnd`, to end a turn does **not** work: the
  server needs to hear silence.)
- **BLOCKED** — the assistant is answering. The mic is kept locally, not sent, and only watched for a
  talk-over.

When the model calls a function the turn completes **twice**: once when it hands over the call and again after
it has spoken the result. Only the second is the end of the exchange.

## Talking over the assistant

The microphone source is `VOICE_COMMUNICATION`, which switches on the platform's echo cancellation and noise
suppression. Measured on one unit, speech peaks around 5,000–11,000 while the assistant's own voice leaks into
the mic at a few hundred at most. Three layers keep the assistant from interrupting itself:

1. **`BargeInDetector`** learns how loud its own echo is during the first 0.6 s of each reply, and raises a bar
   to a multiple of that (and of the ambient level, with an absolute floor). It must be cleared by 5 of the last
   8 chunks.
2. **`DipConfirmer`** — a trigger only turns the reply's volume down for up to 0.4 s. Speech stays loud with the
   speakers quiet; a burst of noise or echo falls away. Only then is the reply cut. A false alarm raises the
   bar for the rest of that reply.
3. **`UtteranceHold`** — if the server is still finishing the reply that was cut, speech sent now is ignored or
   comes back as a fragment, so the user's words are held locally until the old turn completes and are then sent
   in one piece.

## Tools and safety

`ConversationShared.assistantTools` builds the function list. Navigation, opening apps and media keys are always
offered. **Vehicle commands are offered only when the user has switched vehicle control on**, and only commands in
a non-blocked domain are in the registry at all, so the model has no function it could call for anything that
affects driving. The helper process re-checks every command, value and domain, and a dispatch refuses a
blocked domain again. Every command that has a state id is confirmed by reading it back.

The registry is not offered as one function per command (that was about 3,400 tokens of declarations on every
request). Commands are folded by **family**, the part of the id before the first dot, into one function each
(`seat`, `window`, `dashcam`, … fourteen of them); a call carries a `setting` (the rest of the id) and a `value`.
The mapping is `VehicleFamilies.kt`, and the ids, the car's ids and the helper protocol are unchanged. Commands
that are plain Android calls (the media volume) do not need the ADB link: they are offered, and run inside the
app, whatever the switch says, so the audio family is just the volume while vehicle control is off.

Read-only questions (`read_car`: tyres, outside temperature, air quality) travel the same way as `query <id>` to the
helper, which has a fixed list of them (`VehicleQuery`) and calls only getters. They are offered under the same
switch, since the helper is the same ADB-shell power either way.

Vehicle control is **on by default** (it is what the assistant is for); the user can switch it off in Settings.

### The two experimental features

The exterior speaker (`speak_outside`, `play_sound_outside`) and drive-mode switching (`set_drive_mode`) are the only
things that reach past the denylist, and they do it as two narrow exceptions rather than by loosening it. Each is
offered to the model only when **all** of these hold, checked in this order:

1. the build has `BuildConfig.EXPERIMENTAL_CAR_FEATURES` (debug and beta; not stable yet);
2. the car's own framework names the feature with the id it was confirmed with (`CarFeatureIds`, checked again in
   the helper process before anything is written) — on a phone, or another model, the feature is simply absent;
3. the user switched it on under Settings → Advanced → Experimental features (`OptInFeatures` holds the defaults,
   which are off), and vehicle control is on, in a conversation (not a one-shot request).

Hilt supplies them as `Optional<ExteriorSpeaker>` / `Optional<DriveModeSwitch>` (empty when gated off), and the
controller reads the two switches when a conversation starts. Neither goes through the command registry. The
exterior speaker has its own helper process because it *holds* a state (the route, with a heartbeat and a time
limit); the drive-mode request is a single write and runs inside the vehicle helper as `drivemode set <n>`.

### Memory

`memory/` keeps two things in DataStore: *facts* (what the user asked it to remember, at most 20 of 160 characters)
and the *recent exchanges* (the last three, kept ten minutes). When a conversation starts they are turned into a
paragraph of the system instructions, and the model gets `remember` / `forget_memory` to change the facts. Each
half has its own switch; turning short-term memory off also drops what it holds.

## Starting quickly

The time from the button press to "listening" is mostly the connection, so the app does what it can ahead of it:
after a conversation it leaves a pooled TLS connection to the server (`GeminiLiveClient.preconnect`) and refreshes
it for about half an hour; the vehicle helper is started once the session is up, and the exterior speaker's helper
1.5 s after that rather than beside it (two JVMs and a TLS handshake at the same moment were measurably slower). If the
server has made no sound at all five seconds after the user finished, the turn is given up on instead of leaving
twelve seconds of silence. The wait after the user stops speaking is a setting (0.4–1.5 s, default 0.8 s).

## When the connection fails

`ConnectionFailures` classifies what went wrong (no network, key rejected, quota, server refusal, other) from
the exception or the close code. No-network and key problems are shown with a distinct tone and a message in
the app language instead of falling back to the slower classic pipeline. If the server refuses a session that
asked for web search or a custom voice, the app retries without them, search first.

## Language and layout direction

`AppLanguage` wraps the context of the application, the activity and the accessibility service. A window takes
its layout direction from the **application** context, which keeps the language it had when the process
started, so the activity and the banner also set their direction explicitly; without that, switching language
in place changes the text but leaves the layout mirrored. The back button stays on the left in right-to-left
languages on purpose (the driver's side).

Strings live in `res/values` and `res/values-ar`; a unit test fails if the two drift apart. Vehicle command names
shown on the banner are in the registry (`label`, `labelAr`).

## The look

One fixed palette (`ui/theme/Theme.kt`: a near-black page, slightly lighter rounded surfaces, quiet tonal buttons and
one teal accent; dark and light variants, no dynamic colour) is the `AppTheme` of the whole app. The settings pages
are built from `SettingsPage` (two columns on the head unit, one on a narrow screen), `SettingsSection { row { … } }`
(a small label over a card of rows) and the row types `SettingRow`, `SwitchSetting` and `ChoiceSetting` (a value
button that opens a choice dialog). Compose buttons do not play the system tap sound the way Views do, so the
activity root and `AppAlertDialog` (a dialog is a window of its own) carry `Modifier.tapSound()`, which plays it
for a press that something took and that ended as a click.
