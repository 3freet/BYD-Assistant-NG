# Roadmap

What is planned or only a lead so far. Nothing here is built, and nothing here is confirmed on our car unless it
says so. The rule is the same as for every other finding in [byd-internals](byd-internals/README.md): try it on the
car, then document it from our own observation.

## Leads to try on the car

One lead is left. It is a one-shot control in the vehicle HAL, not tried yet. It is a `_SET` id, so there may be no
state to read back; "worked" has to be judged from what the car does.

| Lead | What it might be | First test | Care |
| --- | --- | --- | --- |
| `SENSOR_WINDOWS_CLOSE_REQUEST_SET` (Sensor device, type 1043; write `1`) | Close every window at once | Parked, windows open a little, nothing in the way: one write, watch all four | A window that closes on a hand or a head. Only while parked and with the car's own anti-pinch confirmed to work; closing is a one-way request, so the model should ask first |

If it works it would join the vehicle commands as an ordinary registry entry (with its own safety notes).

The other lead, `LIGHTS_FLASHING_LIGHT_AND_HORN` (the "find my car" flash and honk from the head unit), was tried on
2026-10-17 and is closed: the car refused values 1, 2 and 3, and recordings of the car's own remote "find my car", over the
cloud and over a direct Bluetooth connection, show the command reaching the car without passing through the Android
side; the head unit only sees the lights come on. See [findings-and-dead-ends.md](byd-internals/findings-and-dead-ends.md).

## Features planned

Ideas for the assistant itself, with a priority. None is built, and for each the first step is to check on the car what
it needs before promising it.

| Feature | What it would do | Priority |
| --- | --- | --- |
| **Trip briefing** | Let the assistant brief the driver on recent trips and consumption ("how was my last trip?", "what did I use this week?") from a trip-recording app, linked through an Android intent whose name and reply are still to be defined. Everything stays on the device. | Medium |
| **Routines** | Saved, named routines ("arriving", "cool it down"): one phrase runs a set of controls together (A/C, seat ventilation, ambient light, windows). The model can already chain controls loosely; a saved routine is faster and predictable. Mostly app-side work, on the existing registry and memory. | Medium |
| **"How's the car?"** | A read-only tool for "are the doors locked and shut?", "is any window or the sunroof open?", "how much battery and fuel, and what range?", "is the parking brake on?", "what was my consumption?". The framework names the states (door lock and open states, a statistics device with battery, fuel and EV and fuel range, the parking brake); each reading is confirmed with the read-only probe on the car first, as the tyres were. No writes. | Very low |
| **Seat position profiles** | Save and restore seat positions by name, using the long-term memory the assistant already has. The seat-position ids exist, but this moves things in the cabin, so it is parked-only, with the car's own protection against trapping confirmed and a stop. Needs research on the car first. | Low |

## The greeting when the driver sits down

Built, off by default (Settings → Assistant → When you get in). It speaks unprompted, so it is held to three things that
must all be true, and to nothing else (not an unlock, not the ignition): the car has just woken from sleep, the driver is
in the seat, and the driver's door is shut. Then the assistant says a good morning, afternoon or evening by the system
clock, tells the time and then the outside temperature, warmly and cheerfully, and stops: it asks nothing, and the
conversation ends without a tone. It has a speaker setting of its own, the driver's headrest by default (the car's speakers
on a car with no headrest speaker, or with the voice channel off). With long-term
memory on, the driver can tell the assistant how they want to be greeted and to remember it: different words, something
more to say, or a question, in which case the conversation goes on and listens. A button in the same settings plays it on
demand.

How it works, and why: the apps are stopped when the car is switched off and started again by the wake broadcast, which
comes late (23 s after the screens in the first recording), so the app cannot watch the driver sit down and the door
shut. It reads the seat and the door when it starts (see [driver-presence.md](byd-internals/driver-presence.md)) and
greets as soon as both hold, for up to three minutes after the wake. It needs the assistant's wheel-button service (it
owns the microphone), so it waits for that to bind, and ADB debugging for the reads.

Timing on the car: the first real run took 9.6 s from the wake broadcast to the first word, of which about 3 s was
waiting to retry once the wheel-button service had bound, 1 s a second confirming read and 2 s fixed waits for the service
to bind. Those are gone. The assistant's server session is also opened as soon as the car wakes, while the driver is still
getting in (and let go if the greeting does not happen), so that only the words are left to send; a simulated wake with the
driver already seated went from broadcast to first word in 3.3 s, with the session setup out of the way. On a real wake, with
the app running well before the driver sat down, the first word came 2.1 s after the door shut. What is left is
the helper starting (about 1 s), the service binding, and the model's first word (1 to 1.7 s). Also possible: sending the
words before the headrest route and the cabin-noise reading are done (about 0.4 s), holding the audio until they are.

To check: the delay from the wake broadcast after a night's sleep, and whether the broadcast always comes before the
driver is ready.

## Discussion: the factory test controls ("simulate down")

**What it is.** The car's framework has a group of factory test controls. One of them, "simulate down", lets the head
unit feed a raw message into the car-side layer as if it had arrived from an ECU. Its siblings in the group are the
`TEST_CANIN_TEST_*` frames, shipping information and audio hardware tests. The car's own cluster-debug app can drive
this one from a component that any app on the unit may call, with a raw message as its only input.

**What we know and what we do not.** We know the name and that the entry point exists. We have never sent anything to
it, and nothing reads back from it. We do not know which way "down" points or how far a message goes (which networks,
which ECUs), whether the car checks gear, speed or ignition before it accepts one, whether the messages are checked
or rate-limited, or whether it only works in a factory mode.

**Where it stands in this project.** It is not a feature and it is not planned as one. Nothing in the app can reach
it: the vehicle helper runs only registry commands, the registry holds the audio, bodywork, climate, light and
setting groups and nothing from the test group, the drive, brake, radar, security and power groups are blocked
outright, and anything that does not resolve to a known group fails closed. That stays as it is:
- no tool for the assistant, no helper command and no registry entry that sends a raw message, whatever the use;
- no raw message sent from a car that is driven, and none at all without a reason that is written down first;
- the docs do not list the component, its inputs or the message format.

**Why it is here at all.**
- A raw-message path is a different kind of risk from everything else on this list. A misheard command to a window
  is an inconvenience; a wrong message to an ECU is whatever that ECU makes of it, possibly on the move. An assistant
  that acts on a voice has no business near it.
- The entry point is open to any app installed on the unit, which is a weakness in the firmware, not a feature to
  build on. If it is ever looked into further, the finding belongs with BYD first.
- The same group covers things that look harmless on their own (an audio hardware test, shipping data). They share
  the path, so they share the rule.

**What could be done safely.** Reading, not sending: working out from the car's own code which formats and which
checks exist would tell us how bad the exposure is, and would let us say so plainly if it is. Anything beyond that
would need a bench setup that is not a car in use, and a decision to make here first.

## The assistant's voice on its own audio channel

Built and on by default, on cars that have the stream (Settings → Voice channel): replies on the car's voice stream
with their own volume, the music turned down for the conversation, and a volume that follows the cabin noise. What the
car's own assistant does, what was tried and how it works are in
[voice-channel.md](byd-internals/voice-channel.md).

The voice can also come out of the driver's headrest speaker only (Settings → Sound → Speaker, on a car that has
one): the assistant speaks on the navigation stream, which the headrest sound's Interactive mode plays in the headrest
alone, and the mode is set for the conversation and given back after. Its volume follows the cabin noise on a first fit
of its own. To do: tune the fit in a loud cabin, on a drive.

Still open: whether the car's own dip also handles the radio and Bluetooth music, the "pause" choice, the top of the
volume curve in a very loud cabin, how the voice behaves in the headrest's Exclusive mode, and other models (it is
offered only where the stream has the measured range).

## To test on a drive

Testing only; there is nothing to build here unless it shows something is off.

**The cabin-noise reading's speech band.** The volume that follows the cabin reads only the speech range (about 300 to
3400 Hz) of the cabin microphone, not the whole sound, because a moving car's road and wind rumble made the voice too
loud. The speech band reads lower than the whole band, so it is raised by 6 dB (`SPEECH_BAND_OFFSET_DB`) to sit where the
fitted volume curve expects it for a fan and music. That 6 dB is a guess, to be checked with two runs:
- On a drive at the usual speeds, with the gain slider at 0: is the voice too loud, about right, or now too quiet?
- Parked with the music at the usual level: does the voice sit where it did before?

The log carries both levels in each "Cabin noise now" line (speech band and whole band) next to the "Voice volume" line,
which is what the offset is adjusted from. If driving is still too loud the next step is a flatter curve above about
-45 dBFS. The headrest speaker's own curve is tuned on the same drive.

## Ideas

- Cluster: put a video link's player on the cluster without the one-to-two second stop and rewind a player has
  when it changes screen (see [cluster-screen.md](byd-internals/cluster-screen.md): the cluster reports no
  touchscreen to apps; the passenger screen does not have this).
