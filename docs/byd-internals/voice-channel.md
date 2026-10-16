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

## What is not known

- Whether the car's own dip also handles the radio and Bluetooth music, and how the pause option behaves.
- Whether the voice stream behaves the same on other models and firmware.
- The top of the volume curve in a really loud cabin.
