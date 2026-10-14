# The exterior speaker

Some BYD models have a speaker on the outside of the car (the one used for the pedestrian warning sound). The car
can send an Android audio stream to it instead of the cabin speakers. The app uses that for two assistant
functions: **speaking to someone outside the car** (`speak_outside`) and **playing a short sound outside**
(`play_sound_outside`).

> As everywhere in these notes: observed on one vehicle and one firmware. The feature id below was confirmed by
> the car's own framework naming it, and the behaviour by listening, but not on any other model. The app will not
> use it on a car whose framework does not name the feature the same way.

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
while the route is on. It is what the app reads to see that the switch took.

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
- if no heartbeat arrives for 4 s, or the app's end of the connection closes, or the route has been on for 60 s,
  the helper writes `0` by itself.

The helper is separate from the vehicle helper on purpose: this switch is a held state with a time limit, the
vehicle helper is a plain request-and-answer loop, and the exterior speaker is one of the two features that are
allowed to reach what the denylist otherwise keeps unreachable (see below).

## What the assistant can play

`speak_outside` makes the assistant's spoken reply come out of the exterior speaker (only that reply, and
nobody in the cabin hears it). `play_sound_outside` plays one of nine short effects that are **synthesised in
code** (no recorded samples are shipped): a doorbell, a chime, a bell, three beeps, an alarm, a horn honk, a
whistle, bird chirps and a cat. For anything else the model is told to imitate it with `speak_outside`.

## Safety and scope

- The feature id is in the driver-assistance domain, which the app otherwise refuses everywhere. It is a
  **single, deliberate exception**: one device, one feature id, the values `0` and `1` only. It does not go through
  the command registry, so the model is never offered it as a vehicle command, and the helper refuses any other
  feature or value.
- It is available only when **all** of these hold: the build has the experimental car features, the car's
  framework gives that feature name the id it was confirmed with, and the user has switched it on under
  *Settings → Advanced → Experimental features* (off by default, with a confirmation that says it is experimental
  and at the user's own risk).
- It makes sound that can startle people. Use it with care and within local rules about sound from vehicles.
