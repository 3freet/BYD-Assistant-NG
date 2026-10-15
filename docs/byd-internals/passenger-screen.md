# The passenger screen (FSE)

What the second screen is, what runs on it, and what an app can do with it. Observed on one car (a Fangchengbao
Leopard 5, DiLink 5.1); nothing here was written to the car except launching and closing the assistant app on it.

## What it is

| | Main screen | Passenger screen |
| --- | --- | --- |
| Display | id 0 `ivi`, 2560×1440, internal | id 2 `fse`, 1920×720, external, touch, flags `PRESENTATION`, `OWN_CONTENT_ONLY` |
| Android user | 0 (`Owner`) | 999 (`doubleinstance`), launcher `com.android.launcher3.fse` |
| Screenshot | `screencap` | `screencap -d 1` (the id is the SurfaceFlinger one, not the display id) |

Three more virtual displays (3, 4, 5, 1920×720) belong to `com.xdja.containerservice`, which runs the "fission"
system (`ro.build.system.fission_single_os`); they are screen-projection surfaces, not screens anyone sees.

User 999 has about 120 packages, of which 16 are launchable: car settings, file manager, app store, photos,
the car's own voice assistant (`com.byd.autovoice`), media centre, wireless phone projection
(`com.byd.screenproject`), video player (and Youku), karaoke, and whatever was installed with `adb install`
(this project's app, the trip recorder, the helper apps). An `adb install` lands for both users.

## What works

- **The app opens on it as the passenger user**: `am start --user 999 --display 2 -n <package>/<activity>`.
  It is a separate copy with its own data (first-run welcome, no key, no microphone permission).
- **The main copy opens on it too**: `am start --user 0 --display 2 -n <package>/<activity>`. The real assistant,
  with its settings and state, rendered on the passenger screen ("Vehicle control armed, Tap to talk"). Both
  were closed again with `input -d 2 keyevent KEYCODE_BACK`.
- The screen has its own navigation strip (back, home, apps, settings, power) on the right, 156 px wide.

## Controlling it from the assistant

The `passenger_screen` tool (helper command `fse …`, run under the ADB shell identity) does the following, all
confirmed on the car:

| Action | How |
| --- | --- |
| Open an app | `am start --user <0\|999> --display 2 -n <package>/<launcher activity>`; apps of the main user (YouTube, …) run there too, the passenger user's own apps (video, karaoke, photos, passenger settings…) with `--user 999` |
| Open YouTube on a search | the link handler always starts YouTube's screen on the main display, so the link is opened and the task is then moved: `am display move-stack <root task id> 2` (`--activity-clear-task` is the last resort) |
| Close | `am force-stop --user <u> <package>` for each app that has an activity on display 2 (never the system's own; this app is sent home instead) |
| Home / back | `input -d 2 keyevent KEYCODE_HOME` / `KEYCODE_BACK` |
| Screen off / on | `DisplayManager.turnBacklightOff(2, uptime)` / `turnBacklightOn(2, uptime)`: what the power icon on the screen's own navigation strip does; touching the screen wakes it |
| Status | the focused window of display 2 (`dumpsys window displays`) and its activities (`dumpsys activity activities`) |

`Settings.Global.screen_saver_switch_fse` (0 = black, 1 = clock screensaver) chooses what that power icon does;
the tool always uses black. There is no readable state for the backlight: `dumpsys display` keeps reporting the
display as on.

## What the car exposes for it (read from the framework, not exercised)

`FSE`-named controls and states: screen power and display switch (`POWER_FSE_SCREEN_STATUS_SET`,
`POWER_FSE_SCREEN_DISPLAY_SWITCH_SET`, in the Power group), scene mode (`SETTING_FSE_SCENE_MODE_SET_GET`),
meditation mode request, seat back-rest and leg-rest position, large-bed mode (`BODYWORK_FSE_LARGE_BED_SET`,
moves a seat), roof projection status, camera projection to the screen
(`PANORAMA_SCREEN_PROJECTION_STATUS_IVI_TO_FSE_SET`), music and radio info sent to it
(`INSTRUMENT_FSE_MUSIC_INFO_SET`, `…RADIO_INFO_SET`), and its own atmosphere-lamp and HUD settings.

## Not known

- Whether the microphone is usable from the passenger user, and how audio from it is routed (main speakers or
  the passenger headrest).
- Whether the car restricts what the passenger screen shows while driving.
- How a second app instance would behave with the wheel microphone button (both copies register for it).
