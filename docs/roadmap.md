# Roadmap

What is planned or only a lead so far. Nothing here is built, and nothing here is confirmed on our car unless it
says so. The rule is the same as for every other finding in [byd-internals](byd-internals/README.md): try it on the
car, then document it from our own observation.

## Leads to try on the car

Both are one-shot controls in the vehicle HAL, not tried yet. They are `_SET` ids, so there may be no state to read
back; "worked" has to be judged from what the car does.

| Lead | What it might be | First test | Care |
| --- | --- | --- | --- |
| `LIGHTS_FLASHING_LIGHT_AND_HORN` (Light device, type 1004; write `1`, with a 3 s gap between writes) | The horn route that was never found: the "find my car" flash and honk, from the head unit | Parked, nobody near the car: one write, watch the lights and listen. Read the Light group's states before and after, as in [research-method.md](byd-internals/research-method.md) | It makes a loud noise outside the car and flashes the lights. Keep the 3 s gap; keep it out of the model's tools until it is known to be bounded |
| `SENSOR_WINDOWS_CLOSE_REQUEST_SET` (Sensor device, type 1043; write `1`) | Close every window at once | Parked, windows open a little, nothing in the way: one write, watch all four | A window that closes on a hand or a head. Only while parked and with the car's own anti-pinch confirmed to work; closing is a one-way request, so the model should ask first |

If both work they would join the vehicle commands as ordinary registry entries (with their own safety notes), and
the horn lead would close the "horn cannot be found" entry in
[findings-and-dead-ends.md](byd-internals/findings-and-dead-ends.md).

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
