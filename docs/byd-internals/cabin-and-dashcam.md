# Cabin controls and the dashcam

Air quality, the ambient light strip, the side-mirror switches, media sound and the dashcam: what each reads or
writes, the numbers, and how they were found. The commands themselves are in
[vehicle-commands.md](vehicle-commands.md).

> As everywhere in these notes: observed on one vehicle and firmware (a Fangchengbao Leopard 5, DiLink 5.1). Each
> control was operated on the car's own screen first, the state that changed was recorded, and the same value
> was then written once and read back; every state was put back as it was found.

## Air quality (read-only)

`BYDAutoPM2p5Device` (type 1008). Each getter that answers a pair gives **inside first, outside second**:

| Getter | Answer on this car | Meaning |
| --- | --- | --- |
| `getPM2p5Value()` | `[7, 14]` | PM2.5 in µg/m³, inside and outside (0…3000) |
| `getPM2p5Level()` | `[1, 1]` | the car's rating: 1 excellent, 2 good, 3 light, 4 moderate, 5 heavy, 6 serious |
| `getPM2p5OnlineState()` | 1 | 1 sensors on, 2 off |
| `getPM2p5WarningInfo()` | 0 | 0 normal, 1 cabin above the limit, 2 outside above the limit |
| `getPM2p5AnionState()` | 1 | the negative-ion generator: 1 on, 2 off |

Writing the ion generator (`PM25_ANION_STATE_SET`) was not tried.

## Ambient light

Screen: Light → Inside → Ambient light. The page has an area choice (whole, front, rear), a colour strip, a
brightness bar and two switches.

| What | Typed method (`BYDAutoSettingDevice`) | Values | State read back |
| --- | --- | --- | --- |
| Colour | `setIALColor(area, color, source)` | colour 1…127 (0 is refused); area 1 front row, 2 back row, 3 whole cabin; source 0 screen, 1 voice | `SET_IAL_FRONT_COLOR` (1121976336), `SET_IAL_BACK_COLOR` (1121976343) |
| Brightness | `setIALBrightness(area, level, source)` | argument 0…5 | `SET_IAL_FRONT_BRIGHTNESS` (1121976328) and back (1121976332), reading 1…6 |
| Dynamic colours | `LIGHT_DYNAMIC_COLORFUL_SWITHCH_STATE_SET` (1276153886, Light 1004) | 1 on, 2 off | `LIGHT_DYNAMIC_COLORFUL_SWITHCH_STATE` (679477578) |
| Music rhythm | `SET_MUSIC_MODE_STATE_SET` (1276153881) | 1 on, 2 off | `SET_MUSIC_MODE_STATE` (1121976384); recorded, not exposed |

Writing a colour also makes that area the selected one. The whole-cabin state (`SET_IAL_ALL_COLOR`) is
unavailable (-10011); read the front and back ones.

**The colour strip.** The lamp's own output was read (`LIGHT_FRONT_FOOT_ATMOSPHERE_LAMP_RED/GREEN/BLUE`) for
every fourth index. The strip is not a hue sweep; these are the names the app uses:

| Index | Output (R,G,B) | Name | Index | Output | Name |
| --- | --- | --- | --- | --- | --- |
| 4 | 255,56,255 | pink / magenta | 64 | 223,255,90 | lime |
| 8 | 138,32,249 | purple | 80 | 252,254,40 | yellow |
| 12 | 0,36,236 | dark blue | 84 | 255,220,20 | gold |
| 16 | 40,80,254 | blue | 88 | 241,180,30 | amber |
| 24 | 30,140,243 | sky blue | 92 | 249,150,20 | orange |
| 32 | 1,253,255 | cyan | 96 | 251,105,10 | deep orange |
| 48 | 168,250,208 | mint | 104 | 255,0,0 | red |
| 52 | 110,255,100 | light green | 108 | 247,10,40 | crimson |
| 56 | 24,224,20 | green | 112 | 253,80,60 | coral |
| | | | 116 / 124 / 127 | 255,230,200 / 174,248,255 / 101,240,255 | warm white / cold white / ice blue |

The lamp reading lags a write by about a second, so a reading taken straight after a write can still be the
previous colour. With dynamic colours on the lamp showed the animated scene's own colours at times; turn it off
to see a fixed colour.

## Mirrors

Screen: Driving → Comfortable Use → Rear View Mirror. Two switches, and both are settings, not actions:

| Switch on the screen | Control | Values written | State |
| --- | --- | --- | --- |
| Side mirror auto fold | `SET_CAR_EXTREARMIR_FOLLOWUP_SWITCH_SET` (1312817178), typed `setAutoExternalRearMirrorFollowUpSwitch` | 0 off, 1 on | `SET_CAR_EXTREARMIR_FOLLOWUP_SWITCH` (1081081884) 0/1 |
| Side mirror auto flip while reversing | `SET_CAR_REAR_VIEW_SET` (1312817180), typed `setRearViewMirrorFlip` | 1 on, **2 off** | `SET_CAR_REAR_VIEW` (1228931096) reads 1 / 0 |

The method names are easy to mix up: `…FollowUpSwitch` is the *fold on lock* switch and `…Flip` is the *tilt
down in reverse* one (confirmed by operating each on the screen).

**A manual fold or unfold was not found.** Two controls exist in the framework's feature map
(`SET_OUTSIDE_REARVIEW_MIRROR_FOLD_SET`, 1276157992, and `SPECIAL_REAR_VIEW_MIRROR_CONTROL_SET`, 850444328; the
mirror device `BYDAutoRearViewMirrorDevice`, type 1047, names fold = 1 and unfold = 2). Writing 1 to each was
accepted and the mirror state stayed at 2 (unfolded) with no movement state. The mirror device reports
`REAR_VIEW_MIRROR_STATE` (1 folded, 2 unfolded) and `REAR_VIEW_MIRROR_MOVING_STATE`, and it was seen moving when
the mirror was operated by hand, so the state is readable; the command is not known. Mirror positions
(`BODY_*_REARVIEW_MIRROR_*_POSITION`) read -10013 (not fitted).

## Audio

Screen: Sound & Display → Audio (Devialet Sound Staging, Devialet Space). Recorded while operating it:

| What | Control (Audio 1002) | Values | State |
| --- | --- | --- | --- |
| Sound focus | `AUDIO_LEFT_RIGHT_FIELD_SET_SET` (470810653), typed `setDynaSoundFieldFocus` | whole car 6, front 7, back seats 4 | `AUDIO_SOUND_FIELD_FOCUS_STATE` (1339031565) |
| Space on / off | `AUDIO_SPACE_SOUND_EFFECT_STATUS_IVI_SET` (372244496) | off 1, on 2 | `AUDIO_SPACE_SOUND_EFFECT_STATUS_IVI` (1263534096) |
| Space strength | `AUDIO_SPACE_SOUND_MODE_STATUS_IVI_SET` (372244498) | soft 1, standard 2, strong 3 | `AUDIO_SPACE_SOUND_MODE_STATUS_IVI` (1263534098) |
| Headrest sound (driver's headrest speaker) | `AUDIO_HEADREST_SOUND_EFFECT_STATUS_SET` (-1676672971 = 0x9C100035), typed `setDynaHeadrestSoundEffect` | off 1, surround 2, interactive 3, exclusive 4 | `AUDIO_HEADREST_SOUND_EFFECT_STATUS_IVI` (1253048341) |
| Sound style of the car speakers (Devialet Sound Features) | `AUDIO_DYNAUDIO_SOUND_FEATURES_SET` (-1693450216 = 0x9B100018) | fidelity 1, soft 2, dynamic 3, heavy 4, speech 5, custom 6 | `AUDIO_DYNAUDIO_SOUND_FEATURES_IVI` (1281359880) |
| Media mute | `AUDIO_MEDIA_SOUND_MUTE_SET_SET` (470810680) | normal 1, muted 2 | `AUDIO_MEDIA_SOUND_MUTE_STATE` (1339031597) |
| Navigation volume | `AUDIO_NAVI_VOLUME_SET` (1309081616), typed `setNaviVolume` | 0…10 | `AUDIO_NAVI_VOLUME` (1108344868) |
| All-sound mute | `AUDIO_MUTE_STATUS_SET` (1309024272), typed `setMuteState` | 0 / 1 | `AUDIO_MUTE_STATUS` (1108344846) |

Notes:

- `AUDIO_SOUND_FIELD_FOCUS_SET_SET`, the control that *looks* like the focus one, is accepted and does nothing.
  The screen writes `AUDIO_LEFT_RIGHT_FIELD_SET_SET` (found in the system log: `set featureID is 1c10001d`).
- The all-sound mute works but would also silence the assistant, so the app uses the media mute. Whether the
  assistant's own voice follows the media mute was not heard.
- Navigation volume has three generations of ids (`AUDIO_NAVI_VOLUME`, `AUDIO_NAVIGATION_VOLUME_STATE`,
  `AUDIO_NEW_NAVIGATION_VOLUME_SET_STATUS`). Only the first follows the typed setter; whether the navigation
  app's spoken voice follows it was not heard.
- Devialet is a feature of this car's sound system; another model will not have these.
- **Reading the volume** (`get_volume`): `AudioManager.getStreamVolume(3)` and `getStreamMaxVolume(3)` (39 here), read
  in the app itself; no helper or ADB is involved. The media mute (`AUDIO_MEDIA_SOUND_MUTE_STATE`, 1 on, 2 muted)
  and the navigation voice (`getNaviVolume()`, 0..10) can be read from the audio device but are not exposed.
- **Headrest sound**: the screen (Sound & Display → Audio → Devialet Headrest Sound) offers surround, interactive
  and exclusive; the car also accepts 1 (off) and reads it back. `getDynaHeadrestSpeakerConfig()` is 2 here, the
  left-front headrest only, so it is the driver's headrest speaker that changes. Per-seat headrest volumes
  (`AUDIO_*_HEADREST_VOLUME_SET`) and speaker switches (`AUDIO_LF_HEADREST_SPEAKER_SWITCH_SET`) exist but their
  screens were not found, so they were not exercised.

## The dashcam

The recorder is the system app `com.byd.cdr`, not a HAL device. It publishes a Binder service named
`IBYDCDRService` (framework interface `android.cdrservice.IBYDCDRService`):

| Transaction | Method |
| --- | --- |
| 1 | `setCommand(int command, int value)` returning an int |
| 2 | `registerUser(IBYDCDRUser)` |
| 3 | `unregisterUser(IBYDCDRUser)` |

(The transaction numbers are the stub's constants, **not** declaration order: `setCommand` is 1.) The service
accepts commands 0, 1, 2, 5 and 6 and passes anything else on to registered users:

| Command | Value | Does | Answer |
| --- | --- | --- | --- |
| 0 | 1 / 0 | start / stop recording | 1 done; 24 refused while a locked clip is being saved |
| 1 | – | take a still photo | 1 |
| 2 | – | save the current clip as a locked (event) clip | 2 or 3 |
| 5 | 0 | close the dashcam | 1 |
| 5 | 1 / 2 | open it (and start recording) / open it | 1 |
| 5 | 3 / 4 / 5 | open the album (three views of it; which is which was not checked) | |
| 5 | 6 / 7 / 8 | collision sensitivity low / middle / high | 1 |
| 6 | 1 / 0 | record sound with the video on / off | 1 |

Other answers: 21 the dashcam is disabled in its settings, 22 camera unavailable or the car in a low-power
state, 23 the car is off, 26 could not start, 10 the card check timed out, 11 to 20 card problems (11 no card,
14 full). The framework lists a `BYDAutoSettingDevice.setCDRCommand` that wraps the same call, but it was not found as a
public method with two ints on this car, so the app talks to the service directly.

On the car: audio on/off and collision sensitivity were seen to change the dashcam's own settings screen;
photo, lock, stop and start were seen in its log. A photo or a lock brings the dashcam screen to the front,
because the service starts its activity for those. Locked clips and photos are saved on the dashcam's memory
card; the test runs left a few.
