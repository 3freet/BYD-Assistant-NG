# The exterior speaker

Some BYD models have a speaker on the outside of the car (the one used for the pedestrian warning sound). The car
can send an Android audio stream to it instead of the cabin speakers. The app uses that for two assistant
functions: **speaking to someone outside the car** (`speak_outside`) and **playing a short sound outside**
(`play_sound_outside`).

> As everywhere in these notes: observed on one vehicle and one firmware. The feature ids below were confirmed by
> the car's own framework naming them, and the behaviour by listening, but not on any other model. The app will not
> use them on a car whose framework does not name the features the same way.

## How it works

Three things have to happen, in this order:

1. **Switch the route on.** One write on the audio device:
   `set(deviceType 1002, ADAS_UE_BROADCAST_POSITION_SET = 0xAA000282, 1)`. The name is in the framework's `Adas`
   feature group, so the car files it with the driver-assistance features, even though all it does is choose
   where the *media* output goes. `0` puts it back.
2. **Play on the navigation stream.** The track is a normal `AudioTrack` whose attributes are the car's navi
   stream: legacy stream type `15` (`STREAM_NAVI`), usage *media*, content type `6` (`CONTENT_TYPE_NAVI`) and the
   flags `0x4800`. The public `AudioAttributes.Builder` does not take the last two, so they are written into the
   attributes' `mContentType` and `mFlags` fields. While the route is on, that stream is heard **outside only**.
3. **Switch the route off after the last sample.** The navi output keeps running for about 0.3 s after a track is
   stopped, so the route is held on for roughly half a second after the sound ends, then written `0`. Switching
   off sooner cuts the end of the sound and leaks it into the cabin's navigation output.

A 0.3 s pause between switching on and the first sample avoids losing the start of the sound.

`AUDIO_MEDIA_SOUND_MUTE_STATE` (`0x4FD0002D`, audio device) is a read-only state that reads `1` and goes to `2`
when the **media** route (below) is switched on; the navigation route leaves it at `1` (the car's log: 64 switch-ons of the navigation route
all read 1 -> 1, 65 of the media route read 1 -> 2). It also reads `2` for a few seconds whenever the media bus has been busy (music was
playing a moment ago, or a broadcast was cut short), when a switch-on cannot move it: the helper then takes the route on the write's own
answer (the car's `0`), since a route that did not take is heard in the cabin and never outside.

## How loud

This was the surprising part. Neither the navi stream's volume index (4 against 10) nor the track's own volume
(100% against 10%) changed how loud the exterior speaker was. What changed it was **the audio itself**: a
loudness boost on the track (`android.media.audiofx.LoudnessEnhancer`) was clearly louder at +10, +20 and +30 dB,
with no distortion heard. Unboosted, even at full scale, the sound was too quiet to be heard clearly from a couple
of metres away.

So the three levels in *Settings → Advanced → Exterior speaker volume* are boosts, not attenuations:

| Level | Gain |
| --- | --- |
| Low | +30 dB |
| Medium (default) | +45 dB |
| High | +60 dB |

(A signal scaled *down* from the unboosted level was barely audible, which is why "low" is still a boost.) The
assistant also takes a level from what you say ("say it loudly").

Two side effects to know about:

- A navi stream volume of `0` silences the track altogether (the mixer zeroes it), so the app raises the index
  to `1` for the duration if it is `0`, and puts it back.
- At the high level the sound reaches the **cabin microphone**, so talk-over detection is suspended while
  something is playing outside; otherwise the assistant would hear itself and cut itself off.

## Why a process holds the switch

The route must never stay on by accident: it would send the next navigation prompt or song to the outside. The
write is therefore made by a small helper process running as the ADB shell user (the same reason as everywhere
else in these notes), which **owns the switch**:

- the app sends a heartbeat every 0.5 s while something plays;
- if no heartbeat arrives for 4 s, or the app closes the stream in order, or the route has been on for 60 s, the
  helper writes `0` by itself;
- a switch-off that the car did not take is tried again (for about 5 s) and not forgotten.

All of that works **while the helper lives**. When the connection is cut abruptly (the app is killed, crashes, or is
replaced by an update), the system ends the helper without letting it clean up, and then nothing writes `0` (measured on
the simulator: the shell process is gone and the route stays on). So the app also **remembers** that a route was switched on
(`ExteriorRouteMemory`: written before the route is asked for, and cleared once the switch-off was seen to be taken) and,
when it next runs, or when the helper dies while the app lives, a fresh helper is sent `reset`, which writes `0` to both
routes' features (the media feature on each of its device types). Until then the route stays on: that is the one case the
guard cannot cover.

The helper is separate from the vehicle helper on purpose: this switch is a held state with a time limit, the
vehicle helper is a plain request-and-answer loop, and the exterior speaker is one of the two features that are
allowed to reach what the denylist otherwise keeps unreachable (see below).

## What the assistant can play

`speak_outside` makes the assistant's spoken reply come out of the exterior speaker (only that reply, and
nobody in the cabin hears it). `play_sound_outside` plays one of nine short effects that are **synthesised in
code** (no recorded samples are shipped): a doorbell, a chime, a bell, three beeps, an alarm, a horn honk, a
whistle, bird chirps and a cat. For anything else the model is told to imitate it with `speak_outside`.

## The live microphone broadcast

A second use of the same route, the other way round from the assistant's reply: what the **cabin microphone** hears comes out of the
exterior speaker as it is said, so that someone in the car can talk to people outside. It has its own switch under the exterior
speaker's (*Settings → Car → Experimental features → Live microphone broadcast*, off by default, with its own confirmation), and it is
started by **holding the steering-wheel microphone button for 1 second**; the next press of the button ends it (see
[steering-wheel-button.md](steering-wheel-button.md#holding-the-button)). It needs the exterior speaker, vehicle control and the mic
button's short press as the assistant's trigger (the default). It starts at once: the status pill says it is live from the first
moment, no tone is played first, and the helper that holds the switch is kept ready while the broadcast is armed (it takes a second
to start, and this unit sends the hold as a single event at its mark, with no earlier key-down to start it on). Keeping it ready does not
put the car's "Allow USB debugging?" prompt up again once it was declined or left unanswered (it would come back every few minutes): the
next hold asks again, and a yes starts the keeping ready again. A press while a start still waits for the helper (ADB not allowed yet: the
prompt can be left up for a minute) ends the start at once, and the music that was stopped for it goes on. The route is switched
in about 50 ms (the helper looks at the car's mute state every 15 ms and answers as soon as it has moved, instead of waiting out a fixed
150 ms), and what is local (the audio mode, the track, the echo canceller, the microphone) is got ready *while the route settles* (it
needs 300 ms before a sound is certain to come out of it), so the first sound goes out about **350 ms** after the hold's mark (it was
630 ms), 350 ms plus the music's stop when music was playing.

### The route

Two routes reach the exterior speaker: the navigation stream's (the one the assistant's replies use) and the **media route**, the
`AUDIO_LOCK_CAR_SOUND_EFFECT_PLAYBACK_STATUS_SET` feature (device 1002, 1000 as a fallback) with the music stream. The broadcast uses
the media route where the car has it and the music choice is not *Inside*: on the Leopard 5 the sound written to the track is heard again at
the cabin microphone about **250 ms** later on it (buffer included), against about 0.7 s on the navigation route. The media route is the same feature the
exterior sound apps use for a live shout on this car. It is held by the helper like the first route (the values `0` and `1`, the same heartbeat
and 60 s limit). **It sends the whole music output outside**, whatever is playing on it: see *Music* below.

### What the voice goes through

The cabin microphone (the voice-processed source at 16 kHz, with the unit's own gain control **left on**, two channels averaged where
the unit gives two, the audio mode "in communication" while it is on) goes through `TalkChain`, one chunk of 10 ms at a time, and a
track of at least 160 ms (the least that does not run dry when a chunk comes late). It is the recipe of the exterior sound apps that do
this well, copied step by step. The loudness level (Low, Medium, High) is a plain gain on the voice on the media route (x1, x2 and x4: 0, +6
and +12 dB, never past what the soft clip takes) and the platform's loudness boost on the track on the navigation route (+15, +30 and
+45 dB, see below); the car's volume does the rest.

1. a peak limit at 22000 (16-bit units);
2. the audio processing module's own high-pass (80 Hz) and **WebRTC's mobile echo canceller** (`EchoCanceller`, the native library in
   `app/src/main/cpp`, see `NOTICE.md`): its reference is what was sent out two chunks ago, and it is told the echo arrives 150 ms later
   (a start-up hint, which it refines; it must stay below the real delay, and it follows delays up to about 600 ms, so it works on both
   routes: the hint is 150 ms on the media route and 400 ms on the navigation route, whose way round is longer, and the car's log shows
   it taking 23 to 26 dB out of the echo of loud speech on that route too);
3. a dry mix: when the canceller left less than 0.42 of a loud chunk, up to 18 % of the untouched chunk is mixed back;
4. a hum high-pass at 200 Hz, a whistle low-pass at 3200 Hz (3000 Hz more while the chunk is loud) and a soft clip above 80 % of full
   scale;
5. a **tail fade**: after 22 quiet chunks (the microphone at 1600 or less) the sound is faded 12 % a chunk down to 12 % (-18 dB), and
   it is back at full the moment the microphone is at 5500 or more. It is what keeps the echo of the last word from ringing on.

**The thresholds follow the microphone.** The fade's thresholds (5500 for speech, 1600 for quiet) and the dry mix's (2500) are levels of
the raw microphone in 16-bit units, which the apps this follows have on a microphone that gives a hot voice. This unit's gives a normal voice
at an RMS of 300 to 1700 per 100 ms (peaks around 2500), and 25000 at full shout, so with the numbers as they were the fade never opened and the
voice went out 18 dB down (measured in the car: the sent level sat at -49 dB while the microphone heard -30 dB, replayed offline through
the chain to be sure). The chain therefore follows the loudest the microphone has been lately (`speechPeak`: up by at most 1 % a chunk, so
a click does not move it and a voice does within a second; down by 0.07 % a chunk, a half-life of ten seconds; never below an RMS of 900) and
scales the three thresholds by that against the 8000 they were made for (never above 1). A hot microphone keeps them as they are.

**The echo that is shown.** What the canceller takes out of a chunk is an estimate of the feedback (`lastEchoRms`), but it also takes a
good deal out of a loud shout, so it is capped at half of what was sent a quarter of a second earlier: an echo cannot be more than a
share of its cause.

A **feedback guard** (`FeedbackGuard`) watches the microphone for the one thing that makes this kind of path dangerous, a squeal: a
second of a signal that is loud, steady and tonal (speech comes in bursts and has sharp peaks, a crest factor of 3 and more, against
1.4 for a pure tone) mutes the sound for one second and takes 10 dB off the voice; a second squeal ends the broadcast (the first leaves
the voice at 0.316 of its gain and a second would take it below the floor of 0.12, so it ends it whenever it comes). It was
half a second until a very loud shout (flattened by the unit's gain control and the peak limit into a "tone" for 0.5 s, then falling by
14 dB) was taken for a squeal in the car and cost the rest of the broadcast 10 dB; the shout does not stay steady for a second, a squeal does.

**Why there is no loudness boost.** The assistant's replies are made loud with a `LoudnessEnhancer` of +30 to +60 dB. It is a
compressor with make-up gain, which lifts a barely audible sound to near full scale. The cabin microphone hears the exterior speaker
(about 24 dB under what is played), so a sound 50 dB down went out loud and came back 250 ms later **20 dB louder than it left**: the
broadcast rang by itself within a second, from nothing (measured on the Leopard 5, two tests; a model of the loop with those numbers is in
`TalkChainTest`). With a plain gain the loop gain is below one for every sound, and the echo canceller and the fade only have to do the
rest.

### Music and loudness: the small choices

While the broadcast is live a card of small clickable choices hovers under the level meter (`BroadcastChoices`, an accessibility overlay;
a tap changes it at once, in the broadcast that is going on, and it is kept for the next ones). Two groups, **loudness on the left and music on the
right** in every language (the card is always laid out left to right; only the words in the chips follow their script). The chips make no system
click (see below: on the fast route the output the click would be played on goes out of the exterior speaker).

**Where the music goes** (`MusicMode`): *Paused* (the default), *Outside*, *Inside*. The car has two ways out of the exterior speaker, and the
choice is also a choice of route: the fast media route sends the **whole music stream** outside along with the voice (and the media bus's own
"media sound mute" state moves 1 -> 2), the slow navigation route sends only the navigation stream and leaves the music playing in the cabin.

| Choice | Route | The music |
| --- | --- | --- |
| Paused | fast (media) | stopped first, and let go on after the broadcast |
| Outside | fast (media) | goes out of the car with the voice |
| Inside | slow (navigation, 0.5 to 0.7 s round trip) | keeps playing in the cabin; what the microphone hears of it goes out with the voice, held back by the background rule below (the unit's own echo cancellation does not take the music out) |

**Music inside and the microphone.** The cabin's music reaches the microphone (at an RMS of about 200 to 500 on this unit), and what the microphone
hears is what goes out: on the slow route, boosted by the loudness level (30 dB at the default) and half a second late, which is heard in the cabin
as a loud echo of the music. The unit's own echo cancellation does not take it out (measured: the microphone sat at -44 dB with nothing but music
playing, and it went out at -47 dB), and the echo canceller cannot either, since the music is not what was sent. So while the arrangement is *Inside*
and a media session is playing (looked at once a half second) the chain goes by the **background** (`TalkChain.musicBackground`): it keeps the
quietest 100 ms frame of the last six seconds as what the music and the cabin are, only what is at least six times that (and never more than 0.7 of
the loudest voice lately) counts as voice and resets the gain, and everything else is faded after the usual 0.22 s down to -30 dB, with no
band in between in which the gain is held (a steady music would sit in it for good). The cost is the assistant's own gate's cost: a voice that is
not clearly louder than the music is cut, so one has to speak up over music. The slow route's own echo of the voice is another matter: the echo
canceller does follow it with the 400 ms hint (in the car's log it took 23 to 26 dB out of the echo that followed loud speech).

*Paused* and *Outside* are the same route, so switching between them only stops the music or lets it go on. Switching between them and *Inside*
changes the route while live: the voice is cut and drained, the route put back, the music dealt with, the other route opened, and a track,
an echo canceller and a chain made for it (what one learned of the other route's echo is no use on this one); nothing is sent for about a
second, and the microphone and the speech-level learning go on meanwhile. Where the car has only the navigation route (every release
car without the media route) the music group is left out, since the music is always inside.

**Stopping the music first.** On *Paused* the route is switched once the music has stopped, or after at most 1.5 s if it has not (the broadcast
then goes on with the music still playing, and the log says so):

1. The audio focus is asked for (so that nothing starts the music again meanwhile) and the media sessions that are playing are asked to
   **pause at once** (`MediaSources.pausePlaying`, which needs the notification access the assistant already has for seeing what is
   playing). They are asked rather than left to the focus request because the car's own media center ignores audio focus; one that does
   heed it is paused all the same, and asked to play again at the end (only those that are still paused).
2. What is waited for is the sessions saying that they have paused (one took 0.12 s one time and 0.54 s the next; the wait is at most 1.5 s), or,
   when no session is playing, the list of players on the music stream. Two answers of the system are **not** used, because they were measured on
   the car to be wrong for this: `AudioManager.isMusicActive` reads true with nothing playing (and false while something does), and the list of
   players keeps an AAudio player (Spotify's) as started for some seconds after it has paused (a plain AudioTrack leaves it within 18 ms).
3. Once it is quiet the route waits another 200 ms for what the player had already handed to the car's audio path, which would otherwise be
   heard outside for a moment.
4. Only then is the route switched. With no music playing none of this costs anything.

(The broadcast's own track on the music stream, playing on the fast route, is one of the "players on the music stream" while it goes on, so it is not
counted when the music is stopped in a live broadcast: before, tapping *Paused* while live waited the whole 1.5 s for "music" that was the
broadcast itself.)

At the end it is the other way round: the voice is cut, the route is put back, and only then is the focus given up (the music goes on).
Switching to *Paused* in a live broadcast cannot stop the little that is already on its way: the route is open.

**How loud the exterior speaker is** (`ExteriorVolume`'s three levels as *Low*, *Medium*, *High*, kept apart from the assistant's own setting of
the same name): on the fast route it is a plain gain on the voice, 0, 6 and 12 dB (the car's own volume does the rest, and the platform's loudness
boost is not used there: it is a compressor with make-up gain, and it rang the loop within a second); on the slow route, whose output is too quiet
to be heard unboosted, it is the platform's loudness boost on the track, but in steps of 15, 30 and 45 dB (against the assistant's 30, 45 and 60),
because the boost lifts what the microphone hears of the speaker just as much as the voice. The default is the middle level.

### The tone that says it is live, and which output is which

The broadcast opens with a short, bright rising chirp (`Earcon.LIVE`, about 170 ms), the counterpart of the descending closing tone; it is played
at the hold's mark, at once, with the route still being switched. It has to be heard in the cabin only, and which stream that is depends on the
route, so it was measured rather than assumed: with `streamprobe` (a debug action that plays silence on a track with the attributes of a kind of
sound while `dumpsys media.audio_flinger` is read) the car's mixer puts the **music stream (3), the system's click sounds (1) and the assistant's
plain tones (usage assistant, which lands on stream 3) on one output thread, `AudioOut_D`**, and the navigation stream (15) on another,
`AudioOut_15`. The fast route sends outside what is on the first (the route is switched downstream of the mixer, not per stream: so the
assistant's plain tones, and a screen's click sounds, would go out with the music), the slow route what is on the second. The tone therefore goes
on the navigation stream's output when the fast route is the one to be opened, and on the plain path when it is the slow one. The microphone's first
300 ms of a broadcast are only run through the chain, never sent, which is longer than the tone, so it is not picked up and sent.

### The level meter

While the broadcast is live a card under the status pill (and above the small choices) shows the last four seconds as they scroll by (`BroadcastMeter`, also an
accessibility overlay, never touchable; drawn by hand at the display's rate): a ribbon whose thickness is the voice that is being sent
out (mint-white and thin when quiet, teal and thick when loud; -60 dB is nothing and -10 dB is full), a faint outline of what the
microphone heard behind it (the gap between the two is what the fade and the canceller are holding back), a warm band along the bottom
that is the feedback (what the canceller took out, capped as above: a sliver in a healthy car, amber and then red as the loop gets
close to ringing), and a glowing dot at the right edge that is now. A squeal that is being held back turns the card's edge and the dot
red for a moment. It is fed by `LiveBroadcast.levels`, a frame of three levels every 100 ms.

### Ending, tones, and trying it

It ends by itself after 55 seconds (under the helper's 60, so that it ends the app's way, with its tone and its words), when the helper
link is lost, when the microphone or the route fails, and when the exterior speaker, the broadcast or vehicle control is switched off
meanwhile. **Ending is answered at once**: on the user's press the status pill, the level meter and the music button go away
(`Event.Ending`), the voice is cut (the track is paused and flushed, so what was waiting in it is dropped), and the route is held for
320 ms for what is already in the car's mixer and amplifier, then put back; the music goes on after that, and the tone that says it is
over comes last, about 0.4 s after the press (it was 1.2 s: the track used to play out, then 0.45 s of tail and 0.5 s of grace). The
navigation route keeps the long waits, because its way out is deeper. While a broadcast has the route, the assistant's tones and replies in the headrest wait for it, as they do for an announcement. A
conversation in progress (or a recording) is not interrupted by a hold: nothing starts.

**The slow route's track.** Measured with silence on the car (no route open): the navigation stream's track has a 257 ms buffer on the normal mixer
(not the deep-buffer output), the platform reports 513 ms of latency, and what was written reaches the output 295 to 405 ms later; it ran dry two
or three times a second when it started at 80 ms or 40 ms (a queue of 295 ms with the underruns, or 355 ms without, depending on how the start
fell) and not at all from 120 ms on (403 ms), so that route's track starts at 120 ms. It still ran dry six to nine times in the first moments after a route switch in the car's log (and so did the fast route's
track after a switch back, 8 to 10 times, where its starts at the beginning of a broadcast are clean), so the track is **warmed up on silence**: the
microphone's first 300 ms of a route are only run through the chain, and what is written to the track meanwhile is the silence the chain makes of
them, so the track is already running when the voice comes; the slow route's is also given 150 ms of silence before its first chunk. If it ran dry
while warming up it is started over, still on silence (log: "started over"). The log's line of levels says how the microphone's chunks came (the
longest wait for one, and how many were later than 30 ms), which is what to read if it still runs dry. With the output's delay and what lies after it, the round
trip is about 0.6 s, so the echo canceller's start-up hint on that route is 400 ms (the most the apps this follows allow), where it can still
find the echo (it follows up to about 600 ms: the slow route is at the edge of what it can do, which is why the fast route is the default).

**The delay.** The track used to wait until its whole buffer (160 ms) was full before it played, and then stayed that far behind for as long
as the broadcast went on (the sound waited 200 ms in it, measured with silence and the platform's own timestamps on the car). It now starts
once 60 ms are in it (`setStartThresholdInFrames`, Android 12 and later): 99 ms, a hundred less. Lower is possible (81 ms at 40, 77 at 20)
but gives the microphone's chunks less room to come late, so a track that runs dry is given 20 ms more each time (to 160 ms); the log says so.

Levels are logged every two seconds as `LiveBroadcast: Levels ...` (the microphone, what is left after the echo canceller, what was sent,
how far the fade went, the voice level the thresholds follow), the start as one line (how long the music, the route and the settling took),
and once a line about the track (its buffer, how much of what was written is still waiting, underruns). The debug
build also has, as `am broadcast -a com.bydassistantng.debug.BROADCAST -n com.bydassistantng.dev/com.bydassistantng.debug.DebugBroadcastReceiver --es action ...`:
`tune` (set the numbers above for the next broadcast, no sound), `listen` (record the cabin microphone and log its levels, no sound),
`echotest` (the native library on computed signals, no sound), `choices` (show the small choices without a broadcast and try a tap; `musicbutton` is the old name), `musicwait` (stop the music in the cabin as a
broadcast would and let it go on 1.5 s later, no route), `musicstate` (how fast the system's answers follow a player that pauses), `streamprobe` (which stream and output a kind of sound lands on), `livetone`
(the live tone, in the cabin only), `meter` (the level
meter with made-up levels, a voice in bursts and at the end a squeal), `tracktest` (how long the track keeps sound waiting, measured with
zeros and no route: `--ei start <ms>`), and, which **do
make sound outside the car** and so are only for when someone is by it: `start --ez present true` (`stop` ends it) and `lab`. Every
broadcast of a debug build is recorded as raw 16 kHz PCM in `files/tap` (`mic_`, `aec_` and `out_<time>.pcm`) for offline study.

## Safety and scope

- The exterior speaker is a **deliberate exception** to the denylist: two features on the audio device (the navigation
  stream's, which the car files under driver-assistance, and the music output's, in the audio group), the values `0` and
  `1` only. They do not go through the command registry, so the model is never offered them as vehicle commands, and the
  helper refuses any other feature or value.
- They are available only when **all** of these hold: the car's framework gives the feature name the id it was
  confirmed with, and the user has switched the feature on under *Settings → Advanced → Experimental features* (off by
  default, with a confirmation that says it is experimental and at the user's own risk).
- It makes sound that can startle people. Use it with care and within local rules about sound from vehicles.
