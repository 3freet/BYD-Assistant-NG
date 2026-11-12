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

## DiLink 5.0

Two logs from one owner (a BYD Song Plus, Android 12) show what stopped the app on that generation (see
[platforms.md](byd-internals/platforms.md#status)): the climate, bodywork, setting and instrument device classes refuse the
vehicle helper, which is the ADB shell, for want of a runtime permission it cannot hold (`BYDAUTO_<DEVICE>_COMMON`,
[hal-and-permissions.md](byd-internals/hal-and-permissions.md#dilink-50-the-device-classes-ask-for-a-runtime-permission)). The tyre
pressure, whose class has no such permission, was read. The second log showed that the permission is a normal one, which the app
already holds, and the car's own libraries show that its native service lets the shell through by its uid.

**Built, not yet run on a car: the native route.** Where a device class refuses the helper, the helper makes the same read or
write one layer lower, as a call to the car's native service, for the same registry commands with the same read-back (and the
outside temperature, the inside temperature, the door read and the drive mode, whose speed limit and listed values are checked
first as on any car). The call layout was read from the car's own binaries and the
route has run in a simulator; see [the native route](byd-internals/hal-and-permissions.md#the-native-route) for what it is and how
to check it. The ways not taken, none built:

| Way | What it needs | Against it |
| --- | --- | --- |
| **The app holds the permission and the helper acts on its behalf** | The app already holds `…_COMMON` (a normal permission it declares). The helper hosts a Binder that the app calls; while it handles such a call the check is made against the app's uid, and the car's native side still sees the shell. | A new channel between app and helper, only worth it if the native route fails on a car. |
| **The helper is given a stand-in `Context` that answers the check** | A small class. | It works around the car's check instead of satisfying it. |
| **Root** | A rooted head unit. | Not something the app can ask of an owner. |

| Item | What it needs |
| --- | --- |
| **Check the native route on the Leopard** | The Leopard online and a build with the route: first the read-only check (`native=` in the log line), then a reversible write with `debug.bydang.nativeroute always`, then the drive mode (Eco and back), parked (the steps are in the notes linked above). |
| **The owner's next log** | The Song Plus with a build that has the route: `native=` says whether the service answers, then the window and the A/C commands say whether they take. |
| **Whether `airconditioning` is the climate service** | The `acalias=` field of that log: if its interface is `com.byd.ac.IBydAcService` the A/C service route is used under that name. |
| **The drive mode's speed on a car that refuses the Speed device** | Not expected: the Speed device class has no runtime permission and the Song Plus logs got past the speed check four times. If a car refused it, no drive mode request would be sent (the answer says the speed was not reported), and the limit would need a speed read of its own through the service. |
| **Audio and light on DiLink 5.0** | Neither device has a `…_COMMON` permission in the framework, so the helper may be able to use them there; the logs show no result for either. |

## DiLink 3 and DiLink 4

The app looks every number up by name in the car's own table of features (see [platforms.md](byd-internals/platforms.md)): a
command takes the car's number for each of its names, and a command whose control the car does not name is not offered and
never sent. That has been checked on one DiLink 5.1 car (the table agrees everywhere, so nothing changes there), on
simulated cars and, from its owner's log, on one DiLink 3.0 car (Android 10, `Di3.0_3.5UI`): there the native route took the
window, cabin-light and A/C commands, and the table lacked 19 of the 76 names (see [the status](byd-internals/platforms.md#status)).
It has not been tried on a DiLink 4 car. What is still open:

| Item | What it needs |
| --- | --- |
| **Drive modes on a car without `SETTING_PRESELECTED_DRIVING_MODE_SET`** | **Built, not yet tried on a car:** a second route through the Energy device's operation mode, the six modes its class takes (Eco, Normal, Sport, Snow, Sand, Mud), used when the car's table names `ENERGY_OPERATION_MODE_SET` and `ENERGY_OPERATION_MODE` and not the first route's feature, with the same speed limit and read-back ([the route](byd-internals/drive-modes.md#a-second-route-the-classic-operation-mode)). The DiLink 3.0 owner says the car has drive modes (which ones is not known) and has offered to try it. What it needs: that car's log with the route (its `peek=` shows `Energy.ENERGY_OPERATION_MODE` and `Energy.ENERGY_ROAD_SURFACE_MODE`, the class's separate terrain setting that this build does not use; if the table lacks these names, its `similar=` and `scan.drive=` say what it has instead); on the Leopard, parked, a write of `ENERGY_OPERATION_MODE_SET` read back from `ENERGY_OPERATION_MODE` and `SETTING_TARGET_DRIVING_MODE`, to confirm the encoding on a car we can look at. |
| **The exterior speaker on a car without `ADAS_UE_BROADCAST_POSITION_SET`** | The same owner says the car has one. The Leopard's table holds an Audio family that nothing here has tried: `AUDIO_EXTERIOR_SPEAKER_SWITCH_SET`, `_STATUS` and `_CONFIG`, and `AUDIO_AVAS_AUDIO_SOURCE_TO_EXTERNAL_SPEAKER_SET`. **Not built:** what `_SWITCH_SET` does is not known (it may be the pedestrian-warning speaker's own switch, which must never be left off), the car's framework has no class for it, and the exterior speaker's helper does not use the native service, so a car whose Audio class refuses the shell could not use it. First: the `peek=` field of that car's log (the numbers of `AUDIO_EXTERIOR_SPEAKER_CONFIG`, `_SWITCH_STATUS` and the AVAS source states; the owner's own setting for it, switched and logged again, shows which moves) and its `scan.speaker=`. Then, on the Leopard, parked: the same reads, then whether `_SWITCH_SET` sends the navigation stream outside as the Adas one does (someone outside to listen). Only then a route for it, which restores the state it found and never leaves anything off. |
| **Confirm it on a car** | Someone with a DiLink 4 car, or a DiLink 3 one to say whether the glass moved, parked, to try the window open/close and position controls and send the app log (*Settings → App → View app log → Save to Downloads*, private details hidden). The header of the saved file and the helper's `Vehicle helper found …` line say what the car's own table holds and, by name, which of the registry's numbers it agrees with (`names=`), numbers differently (`differ=`, the car's number is the one used) or lacks (`absent=`, and `withheld=` for the commands that costs). |
| **What the properties hold** | The log line shows `ro.vehicle.type` and the other identification properties of that car. On our DiLink 5.1 car `ro.vehicle.type` is `Di5.1_5.0UI` (`<platform>_<interface>`), so its first part names the platform. They are logged and do not decide, because the numbers are what the app sends; what DiLink 3 and 4 cars put there would show in their logs. |
| **A DiLink 3 window that does not move** | A car may accept the open/close control without moving the glass while the percent control works; we have not seen one. The app does not notice an `open` that moved nothing; it would read the window's position before and after and fall back to the percent control. Needs a car that does this. |
| **The generic call on DiLink 3** | If a DiLink 3 log shows the helper cannot find the generic `set` or the device classes (the `calls` and `classes` fields of that line), the commands would have to reach the car through the `autoservice` system service instead. Not built: it needs a car to confirm. |
| **The features that write to the car and were verified on one car** | The drive mode, the exterior speaker and the instrument cluster stay off on a car whose table does not give their names the verified numbers (see `ExteriorSpeakerSwitch.isOnThisCar`). They could take the car's own number by name like the registry's commands, but each is guarded by a check of the exact id, which would have to follow; not done. |
| **A Toyota-protocol car** | The car's own class numbers features from `sys.car.protocol` (CAN-FD, Toyota or classic CAN). A Toyota-protocol car matches neither of the two schemes the platform check knows, so it reads as not recognised, and the commands are looked up by name all the same. Whether the platform check should name that scheme is open; it only changes how careful the app is. |

## Features planned

Ideas for the assistant itself, with a priority. None is built, and for each the first step is to check on the car what
it needs before promising it.

| Feature | What it would do | Priority |
| --- | --- | --- |
| **More AI models** | Today the assistant talks to one provider (Google's Gemini Live API), and its live voice, tools, memory and the greeting are built on it. Plan and add others, such as Claude and more, as a choice in the settings (a key per provider). To settle first: most other models have no live-audio API, so they would need speech in (a speech-to-text step), the model, and speech out, and the head unit has no speech engine of its own, so the voice would come from a cloud service; what each can do with the tools (car controls, memory, the greeting), and what it costs per conversation. | To plan |
| **Trip briefing** | Let the assistant brief the driver on recent trips and consumption ("how was my last trip?", "what did I use this week?") from a trip-recording app, linked through an Android intent whose name and reply are still to be defined. Everything stays on the device. | Medium |
| **Routines** | Saved, named routines ("arriving", "cool it down"): one phrase runs a set of controls together (A/C, seat ventilation, ambient light, windows). The model can already chain controls loosely; a saved routine is faster and predictable. Mostly app-side work, on the existing registry and memory. | Medium |
| **"How's the car?"** | A read-only tool for "are the doors locked and shut?", "is any window or the sunroof open?", "how much battery and fuel, and what range?", "is the parking brake on?", "what was my consumption?". The framework names the states (door lock and open states, a statistics device with battery, fuel and EV and fuel range, the parking brake); each reading is confirmed with the read-only probe on the car first, as the tyres were. No writes. | Very low |
| **AUX volume dip** | Turn the AUX input down while the assistant speaks, as the radio and Bluetooth music are. AUX has a volume of its own and no media session to say it is playing, so the app cannot tell when something is; it would have to be turned down whenever the assistant speaks, or by listening for sound on the input. Few cars have anything plugged in now. | Low |
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
greets as soon as both hold, for up to 30 minutes after the wake: the car also wakes for a remote unlock or a pre-cooling and
can wait ten minutes or more for the driver (a first version gave up after three, and missed a driver who arrived after seven
and a half). While nobody is about (the seat empty, the door shut) it looks only every two seconds, since the door has to open
before anyone can sit; it opens the assistant's server session when the door opens, not at the wake. It needs the assistant's wheel-button service (it
owns the microphone), so it waits for that to bind, and ADB debugging for the reads.

Timing on the car: the first real run took 9.6 s from the wake broadcast to the first word, of which about 3 s was
waiting to retry once the wheel-button service had bound, 1 s a second confirming read and 2 s fixed waits for the service
to bind. Those are gone. The assistant's server session is also opened as soon as the car wakes, while the driver is still
getting in (and let go if the greeting does not happen), so that only the words are left to send; a simulated wake with the
driver already seated went from broadcast to first word in 3.3 s, with the session setup out of the way. On a real wake, with
the app running well before the driver sat down, the first word came 2.1 s after the door shut. What is left is
the helper starting (about 1 s), the service binding, and the model's first word (1 to 1.7 s). Also possible: sending the
words before the headrest route and the cabin-noise reading are done (about 0.4 s), holding the audio until they are.

A greeting that cannot start (no connection to the server yet, with the phone's hotspot still coming up, a slow or a refused
connection) is tried again every four seconds, for one minute at most from its first try after the wake, and then given up for that
wake. The minute is counted once: a flicker of the seat or door reading does not start it again, and a try that could still be going
on after it is not begun. A first version stopped after ten tries, 45 seconds, and one morning that was not enough; the next kept
trying for 90 seconds from the driver being ready, and started the time again at every flicker, so that on a car with no connection
the "connecting" banner came back on the screen for as long as the reading flickered.

To check: the delay from the wake broadcast after a night's sleep, whether the broadcast always comes before the driver is
ready, and a driver arriving 10 to 30 minutes after a remote unlock.

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
it: the vehicle helper runs the registry's commands, three command sets of its own (the drive mode, the passenger screen and the
instrument cluster: each a fixed list of actions, none of them a raw message) and read-only queries; the registry holds the audio, bodywork,
climate, light and setting groups and nothing from the test group, the drive, brake, radar, security and power groups are blocked
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

Still open: AUX is not dipped (see the table above; the radio and Bluetooth music are: they are seen through the media sessions, and the switch "Also when nothing is listed as playing" is the fallback for the media volume), whether the car's own dip handles them, the "pause" choice, the top of the
volume curve in a very loud cabin, how the voice behaves in the headrest's Exclusive mode, and other models (it is
offered only where the stream has the measured range).

## To test on a drive

Testing only; there is nothing to build here unless it shows something is off.

**The cabin-noise reading's speech band.** The volume that follows the cabin reads only the speech range (about 300 to
3400 Hz) of the cabin microphone, not the whole sound, because a moving car's road and wind rumble made the voice too
loud. The speech band reads lower than the whole band, so it is raised by 6 dB (`SPEECH_BAND_OFFSET_DB`).

The first drive with it (2026-10-18, with music up on the motorway) was much better than before but still loud, and louder
with music playing, and the headrest was a little loud at speed. Changed since: the line bends above -48 dBFS (the road
counts for less than its level), the first reading with music playing has the music's dip taken off it, and the headrest
line bends too (see [voice-channel.md](byd-internals/voice-channel.md)). A second drive found slider 0 a little loud on the
road and +6 very loud, and the headrest a step loud: two steps (one for the headrest) are now taken off in proportion from
-55 to -45 dBFS, and the car-speaker slider's zero moved two steps down (labels still -6 to +6). To check on the next drive: motorway levels, with and without music, in
both speaker choices; and whether the reply still cuts itself off.

The reply cutting itself off with music and road noise is not explained yet. In 20 minutes of the drive the detector fired 12
times, most followed by something the driver said, but not all, and the echo of the assistant's own voice into the microphone
was far higher at motorway volume (a coupling of 0.07 to 0.41, against 0.001 to 0.03 in a calm cabin; the detector counts
anything above 0.25 as the driver). A quieter reply at speed should cut that; if it does not, the detector's bar needs to
rise with the noise. The log keeps the time of each one (the "Barge-in" lines), which is what to note down.

Found since, in the same log: a reply the server had already sent could start playing 10 to 14 ms after "Conversation
ended" (six times in the evening and morning), over whatever came next, which sounds like two assistants at once. Events
waiting to be handled were handled after the flush with the new playback generation; the conversation is now marked ended
before the flush, and a reply that arrives after that is dropped.

**Music in the background.** Sung words can reach the microphone and be taken for the user (Settings → Conversation → Music
in the background, both off by default). The level gate (`LevelGate`) learns the music and cabin level just before the user
speaks and sends only what is clearly above it (1.7 times at the middle sensitivity, 1.5 to 2.1 across the steps), as silence
otherwise; the other switch adds a line to the assistant's instructions saying sung words are not the driver. Neither has been
tried on a drive. To do: with a song with vocals playing, open the assistant and stay silent for 12 s and read from the log
what it heard and at what level, then speak over the music at the usual volume and read the "level gate silenced X of Y
chunks (bar N over a background of M)" part of each "User stopped speaking" line; tune `BASE_MULTIPLIER`, `MIN_BAR` and the
percentile from that, and decide whether either switch should be on by default.

## Ideas

- Cluster: put a video link's player on the cluster without the one-to-two second stop and rewind a player has
  when it changes screen (see [cluster-screen.md](byd-internals/cluster-screen.md): the cluster reports no
  touchscreen to apps; the passenger screen does not have this).
