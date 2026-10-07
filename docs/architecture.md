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
| `audio/` | Microphone and speaker I/O, end-of-speech detection, talk-over detection |
| `vehicle/` | The command registry, the safety gate, and the helper process (see byd-internals) |
| `apps/`, `media/`, `navigation/` | The non-vehicle tools: open an app, media keys, navigation |
| `ui/` | The screens: home, onboarding, settings, about (Jetpack Compose) |
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
