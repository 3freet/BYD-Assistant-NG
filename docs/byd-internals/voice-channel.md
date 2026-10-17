# The voice channel

The car has an audio stream for a voice assistant, apart from the music: its own volume, and a way to have the music
lowered while the assistant is active. The car's own assistant speaks on it. The app can speak on it too, so that the
reply has a volume of its own, follows the cabin noise and is not at the mercy of the music's volume. It is on by
default (Settings → Voice channel), with the music turned down by half and the volume following the cabin noise, and
is offered only on a car whose voice stream is the one measured here (index 5 to 24); on any other car the section is
absent and nothing changes.

> As everywhere in these notes: observed on one vehicle and one firmware, with YouTube and Spotify as the music. The
> streams, the dip and the volume curve below were measured on that car and need re-measuring on another.

## What the car's own assistant does

Read from the code of its apps (the assistant did not respond on our car, so nothing of this was heard live):

- Its spoken replies are a plain `AudioTrack` (24 kHz, mono, 16-bit) on the legacy stream type **17**, the car's
  `STREAM_BTTS`, in the branch taken for our vehicle type. Its navigation prompts use stream 15 and its short
  interface sounds use sonification attributes.
- The car's audio service maps streams to audio *contexts*: media (1), navigation (2), voice (3), ring (4), call (5),
  and the radio and Bluetooth music (10002, 10001) on streams 12 and 14. Stream 17 is the voice context.
- Around a reply it calls a BYD addition to the audio manager, `setAutoVoiceActiveState(active, duckMedia)`, or, if
  that is missing, takes ordinary audio focus with the attributes the car's audio service gives for the voice
  context. The speech app itself asks for no focus.

| Stream | Index range | Used for |
| --- | --- | --- |
| 3 | 0 to 39 | The car's volume (media) |
| 15 | 0 to 10 | Navigation prompts, and the exterior speaker ([exterior-speaker.md](exterior-speaker.md)) |
| 17 | 5 to 24 | The assistant's voice |

## What an app can do with it (tried on the car)

- A track on stream 17, built either with the legacy constructor or from attributes with the legacy stream type
  set, plays in the cabin. The car labels it with its own content type (`CONTENT_TYPE_BTTS`) and the voice volume
  group. The media volume does not move it, it does not move the media volume, and the volume keys steer it while
  it is playing.
- An app can read the stream's range and set its index, and put it back.
- The voice-active call returns 0 when an ordinary app makes it, in both directions, and changes nothing that could
  be measured: no volume moved, nothing was listed as ducked, and the music did not dip.
- **Audio focus with ducking does lower the music.** A focus request that allows ducking, with the stream-17
  attributes, was granted, and the music dipped. What happens depends on the music app: YouTube's player is
  ducked by the system (listed as ducked, then un-ducked), while Spotify is *notified* of the loss
  (`LOSS_TRANSIENT_CAN_DUCK`) and lowers itself. The dip is deep and has no setting. With Spotify the music cuts
  out for about a second as the focus is given back, with its player still playing throughout (a level change in the
  app or the car's audio path, not a pause), and the app cannot remove it.

## How the app uses it

- **Replies** are played on stream 17. Each reply starts a track and lets it go once it has been heard: a track left
  open between replies keeps the voice stream "in use" for the whole conversation.
- **The tones** (listening, thinking, done, error) play on the same channel, so they follow the voice volume.
- **The music** is dealt with from the moment the conversation opens (so it is already down while the assistant
  listens) until it ends. The choices: leave it, turn it down by a share (the default), the car's own dip (the focus
  request above), or pause it.
  - *Turn it down by a share* lowers the media volume index by 10 to 90 % (default 50), in a fade of about four
    tenths of a second, and brings it back the same way. It does this only while music is playing, and leaves the
    volume alone if the user has changed it meanwhile. "Music is playing" is judged from the players the system
    lists as active (media or game, not the assistant's own voice, content type 10, and not a navigation prompt,
    content type 6): `AudioManager.isMusicActive` answers true all the time on this unit, with no player started at
    all. The index is written to disk while it is down, so that a
    stopped app puts it back at the next conversation. It does not touch the radio or Bluetooth music, which are on
    other streams, and at a music volume of 1 or less there is nothing to turn down.
- **The reply's volume** can follow the cabin noise (a switch, with a gain slider of -6 to +6 steps).

## The cabin noise

The conversation's own microphone is the voice-processed source (echo cancellation, noise suppression, gain control),
so what it hears says little about how loud the cabin is. The app listens with a second, **unprocessed** microphone
(falling back to the plain one) for as long as the conversation lasts. Two microphones in one app work together on
this unit. It keeps the level of each 50 ms window, only while the assistant is not speaking (its own voice would be
taken for noise), and the cabin noise is the **lowest quarter of the last three seconds**: the user's own speech and
a bump are loud windows among quiet ones, so a low percentile reads through them. It is read when a reply starts, so
nothing waits, and it is fresh for every reply. Nothing of this audio is kept or sent anywhere.

The level is read in the **speech band** (about 300 to 3400 Hz, two second-order filters), not over the whole signal. A
moving car's road and wind noise is mostly low rumble, which is loud on a plain level meter yet hides a voice far less
than a fan's hiss or music does; the first version read the whole band and, on a drive, the voice came out too loud (14
at -45 dBFS, where the driver had the slider all the way down for 7 or 8). The speech band reads lower than the whole
band (7 dB lower in a silent cabin), so it is raised by 6 dB (`SPEECH_BAND_OFFSET_DB`) to sit where the fitted curve
(below) expects it for a fan and music. That offset is a first estimate: the log carries both levels ("Cabin noise now
-52.0 dBFS in the speech band, -50.1 over the whole band"), to check against a drive and to adjust.

Readings on the car (whole band): about -66 to -57 dBFS parked with the music low, -48 to -41 with the music up, and -53
to -27 on a drive.

The volume for the noise is a straight line from index 5 at -62 dBFS to 24 at -22 dBFS, plus 1 step, plus the slider.
The line was fitted to the volumes the driver chose across those conditions (with the slider at -5: 6 at -60, 7 at -57,
10 at -51, 14 at -42, 15 at -40, 16 at -38). The step is what is left of two corrections: 2 steps were taken off because
the voice was still slightly loud with the slider at 0, and 3 were put back once a calm cabin's volume could go below the
stream's lowest index (below), when it became too quiet. The curve has not been checked above about -27 dBFS, and the two
ends, the slope and the offset are the part to re-measure on another car.

**Below the lowest index.** In a calm cabin the curve asks for less than index 5, the stream's lowest, and the slider
would then do nothing. So the stream stays at 5 and the rest is taken off the track itself, 2 dB for each step the
curve is below 5 (about 10 dB with the slider all the way down). How loud one step is was measured with a steady
tone at the unprocessed microphone, in a calm cabin (-58 dBFS), the tone's own level after taking the cabin out:

| Index | 5 | 6 | 7 | 8 | 10 | 12 | 14 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Tone at the microphone (dBFS) | about -66 | about -66 | -55.8 | -53.8 | -47.6 | -44.7 | -41.9 |

That is about 2 dB a step from 7 upwards, and more (4 or so) at the lowest indexes, where the tone is close to the
cabin's own noise and the reading is rough. The tones follow the same track attenuation as the voice.

## The headrest speaker

The car's headrest sound (the driver's headrest speaker; see [cabin-and-dashcam.md](cabin-and-dashcam.md#audio)) is
done by the amplifier, not by Android: the audio policy lists one output, "Speaker", nothing in the framework's audio
attributes names the headrest, and the sound devices are closed to the shell identity. The car's own screen describes
the modes as surround (the headrest joins the car speakers), driver or *interactive* ("navigation and intelligent
broadcasting" in the headrest) and exclusive (music in the headrest). Heard on the car (the driver in the seat, quiet
tones, one stream at a time, repeated):

| Headrest sound | Voice stream (17) | Navigation stream (15) |
| --- | --- | --- |
| Surround | all the speakers | not tried |
| Interactive | **the car's speakers, not moved** (heard three times) | **the headrest only** (heard twice, "for sure") |
| Exclusive | not decided | not tried |

An early round was read as "the voice stream is in the headrest in Interactive"; it was a mix-up of which group of
beeps was which, and the real conversation and two repeats said otherwise. What the car's own Interactive description
calls the *call* sound is a different stream again: the head unit's volume panel has four sliders, Media, Navi, Voice and
Call, which are streams 3, 15, 17 and **0** (`STREAM_VOICE_CALL`, range 1 to 39; found by matching each slider's fill to
its stream's index and by moving the Call slider and reading every stream). A plain track on stream 0 is sent to the same
speaker device as everything else and was **not** heard in the headrest alone in Interactive; a variant with the audio
mode set to communication was played too, but its result was not recorded, and the call stream was dropped in favour of
the navigation stream. So the voice stream cannot be sent to the
headrest, and the navigation stream can. That is what *Settings → Sound → Speaker → Driver's headrest* does:

- The assistant's replies and tones are played on the **navigation stream** (legacy stream 15, plain attributes as the
  voice stream's are; the car labels it with its navigation content type) instead of the voice stream.
- Its volume (0 to 10, the car's navigation volume) follows the cabin noise on a fit of its own. One step is about 2.4 dB
  (a steady tone at the unprocessed microphone, the headrest on Interactive, cabin -61 dBFS: index 1 to 10 read -55.0,
  -53.1, -50.8, -48.1, -46.0, -43.6, -41.1, -38.7, -36.1, -33.6 dBFS). The curve runs from index 3 at -62 dBFS (5, the
  navigation volume the driver had, was louder than wanted in a calm cabin) to 10 at -32 dBFS, about 0.6 dB for each dB
  of noise, since the speaker is close to the ear, and the slider moves it. Index 0 silences the stream, so the curve stops at 1 and the track is made
  quieter below that. A first fit, not yet tuned on a drive.
- The navigation volume is the one the car's navigation prompts use, so the app writes down what it was when it first
  changes it in a conversation, and puts it back 1.5 s after the last sound of the conversation, if the driver has not
  changed it since (and the next conversation puts it back if the app was stopped meanwhile). With the cabin-noise volume
  off the navigation volume is left alone, and at 0 the reply is silent.
- The volume is set **before the first tone** of a conversation, for the voice stream as well as the headrest's. Before,
  the first tone played at whatever the stream had been left at, and the volume followed the cabin only from the first
  reply. The cabin is listened to for 0.4 s while the connection is being opened (the microphone takes about 0.3 s to open,
  so the reading is in at about 0.7 s, as the connection is); the start never waits for it more than 0.15 s past the moment
  everything else is ready, and if it is not there the cabin as it sounded at the end of the last conversation is used when
  that was less than ten minutes ago. Measured: the conversation is ready to listen after 0.88 to 0.93 s with it, against a
  median of 0.88 s over 139 starts without. (A first version waited for a 0.7 s reading and made the start 0.5 s later.)
- The helper that sets the headrest sound is the car-control helper, which is shut down after five minutes unused; with
  the headrest as the speaker it is kept for thirty, so a conversation within half an hour of the last starts it warm. One
  that finds it stopped pays for starting it (about 0.5 s more before "ready to listen").
- The exterior speaker uses the same stream with its own switch: a reply still playing is let finish before the switch
  goes on, the listening tone and the replies wait for the switch to go off again, and the announcement itself is also
  heard in the headrest while Interactive is on. A **sound effect** played outside holds the route from before its switch
  goes on until it has gone off, and a reply that starts meanwhile waits for it (up to 8 s). Before that, the reply started
  at once (the model answers as soon as the tool returns), and its first second or so, "the cat sound was played", came
  out of the exterior speaker: the log showed the reply's first sound 0.8 s after the switch went on and 1.4 s before it
  went off.
- The headrest sound has to be on Interactive for that. When a conversation opens the app reads the mode
  (`AUDIO_HEADREST_SOUND_EFFECT_STATUS_IVI`, the state of the registry's `audio.headrestSound`) and, if it is not
  already Interactive, writes down the mode to give back and sets Interactive. The switch is made while the connection
  to the server is opened, and the listening tone waits for it for up to three seconds, so the tone itself is heard in
  the headrest (about a quarter of a second once the helper runs).
- Four seconds after the conversation's last sound (so that a press straight after does not switch it back and forth) the
  mode is given back, but only if the car still reports Interactive: a mode the user picked on the screen meanwhile is
  left alone.
- What is to be given back is written to disk before the change, so a conversation the app never finished (it was
  stopped, or the head unit went to sleep) is undone the next time anything here runs.
- The choice is offered only on a car that reports a headrest speaker (`AUDIO_HEADREST_SPEAKER_CONFIG` above 0; it reads
  2, the left front headrest, here), asked once through the same helper as the car controls and kept. It needs the
  voice channel switched on and, like the car controls, ADB debugging.
- While it is on, navigation prompts from a map app are also heard in the headrest (that is what Interactive is), and
  music stays in the car's speakers. The music is still turned down by the chosen share; whether the car adds its own dip
  for a track on the navigation stream was not looked at.
- Not covered: the exterior speaker (it speaks on stream 15 too, with its own switch; not tried together with this), and
  the classic text-to-speech fallback, which does not use either stream.

## What is not known

- Whether the car's own dip also handles the radio and Bluetooth music, and how the pause option behaves.
- Whether the voice stream behaves the same on other models and firmware.
- The top of the volume curve in a really loud cabin.
