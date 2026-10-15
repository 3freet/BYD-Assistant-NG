# Quirks, and the things that did not work

Written down so nobody spends the same hours again. All of it was observed on one vehicle (see
[README](README.md#where-this-came-from)).

## Quirks

**Fridge temperature is a signal, not degrees.** One shared signal carries both modes: cooling runs 13–25 and
the screen shows −6…+6 °C (screen = signal − 19; the default signal 22 is 3 °C), while heating runs 35–50 and
the screen shows the same number. The per-mode registers (`…_COLD`, `…_HEAT`) are separate again (the heat one
is signal − 34). A temperature set while the fridge is in the other mode is not applied, so the app refuses it
unless the mode state matches.

**Seat massage cannot be switched off with intensity 0.** Writing `1`, `2` or `3` to the intensity control
starts the massage (the car sets its own mode to 5). Writing `0` is accepted and ignored — six attempts left
the level where it was. What the native screen does is write **mode = 1**, after which the level reads 0. The
app encodes this as a per-value override (`overrides` in `vehicle_commands.json`).

**Seat heating and ventilation are a three-value state on the Setting device**, `1` off, `2` low, `3` high. The
native button cycles `1 → 3 → 2 → 1`, which is how the two "on" values were told apart. The A/C service has its
own seat sub-service, but the meaning of its `type` argument is unknown, so it is not used.

**Windows have a percent state.** `BODYWORK_WINDOW_*_PERCENT` reads 0 when closed and rises as the window
opens; the matching `…_WINDOW_TARGET_POSITION_SET` controls take the same 0–100. The rear-left window started
moving about three seconds after the command while the others reacted in under a second, so a read-back after
a couple of seconds can report "nothing moved" for a window that is about to.

**The cabin light** is `SET_INSIDE_LIGHT_STATE`: `1` off, `2` on (it reads `0` for an instant going off). A
separate pair controls the "follow the doors" mode. The ceiling "middle" lights read 65535 on this car.

**The A/C changes its own mind.** Ventilation, front defrost and a few others set several properties at once and
drop the system back into auto when they end. See [ac-service.md](ac-service.md).

**An `AccessibilityService` is removed when its app is force-stopped,** and the quick-boot at wake force-stops
everything. See [steering-wheel-button.md](steering-wheel-button.md).

## Things that did not work

**The horn cannot be observed or found.** Pressing the wheel horn changes no state, produces no input event and
logs nothing that names a horn; the only trace is a ~10 ms pulse in the steering-wheel-speed signal, which is
the wheel pad flexing. The car's "find my car" (lights and horn) showed only the lights: the turn signals went
to the fast-flash value (`TURN_LIGHT_FLASH_FAST` = 9) for about eleven seconds and the front wing lights came
on. The honk left no state.

**The only candidates for a remote horn are in the Security group** and are `_SET`-only, with no state to read:
`SECURITY_HIGH_RISK_WHISTLE_WARNING_SET` and `SECURITY_HIGH_RISK_DOUBLE_FLASH_WARNING_SET`. The Security device
class (`BYDAutoSecurityDevice`, type 1010) has no horn, whistle or flash method or constant, only
`SECURITY_STATE_SAFE = 0` / `WARNING = 1`, and its `getFeatureList()` returns null and `getSecurityState()`
an empty array. Writing `1` to the double-flash control was accepted (return 0) and produced no visible light
change in the following fifteen seconds. Their names read like *settings* ("warn on a high-risk event")
rather than "flash now" buttons. **We do not know what they do, and writing to them might change a stored
security setting.** This project deliberately keeps the whole Security domain unreachable; please do not
experiment with it on a car you cannot afford to confuse.

**No manual mirror fold was found.** The two controls the framework lists for it were accepted and nothing moved or
changed (see [cabin-and-dashcam.md](cabin-and-dashcam.md#mirrors)); only the two *settings* (auto-fold on lock,
tilt in reverse) are controllable.

**A control that looks right can be accepted and ignored.** `AUDIO_SOUND_FIELD_FOCUS_SET_SET` takes any value and
changes nothing; the screen writes `AUDIO_LEFT_RIGHT_FIELD_SET_SET` for the same choice. Find what the screen
writes in the system log (`set featureID is …`) rather than guessing from names.

**The cluster's normal / sport gauge view could not be switched from the head unit.** The steering-wheel switch
shows up on the head unit only as a cluster message (type 11 on the cluster intercom, subsystem 1, command 1001,
parameter 1 for the first switch and 2 for the second, logged by `BydResMgr-ClusterMgr`) and as pulses of
`BODYWORK_REARVIEW_MIRROR_CONFIRMATION_KEY`; no readable state changes (`getInstrumentView` stays 1,
`getViewStatus` is 65535 in both views). Writing the typed `setViewSwitch(1)` and `(2)` (`INSTRUMET_2IN1_VIEW_SWITCH_SET`)
is accepted and nothing changes. The car's own `com.byd.clusterdebug` app sends numbered commands to the cluster
through the `auto_container` service (`AutoContainerManager.sendInfo(1000, command, "")`; the shell identity is
refused by its caller check, an installed app is not); its racing-mode style commands 278 and 279 changed nothing
visible either, with the cluster in normal view and again in sport gauges. Classic / tech mode (8, 9), day / night
(6, 7) and the theme setters were not tried. The same list holds commands that blank the cluster (1, "disconnect the
cluster video") or run a stress test (41), so do not send numbers from it at random.

How the cluster is fed on this car (observed): `com.xdja.containerservice` publishes three virtual displays,
`fission_bg_XDJAScreenProjection` (id 3), `shared_fission_bg_XDJAScreenProjection_0` (id 4) and `_1` (id 5), each
1920x720 with the layer stack equal to its id, all empty by default. An activity started with `am start --display 4`
is accepted and runs there, and nothing appears on the cluster. `com.byd.sr` and `com.byd.launchermap` are not
installed on this car; the map meter activities live in BYD's navigation app `com.byd.naviauto`, which is disabled on
this car. The container service decides who may send to the cluster from `/system/etc/container_comm_cfg.json`: only
the packages listed there (the theme store may send message type 8, which carries cluster skin requests) and apps
signed like the service. The shell identity is not among them. What does put an app on the cluster is described in
[cluster-screen.md](cluster-screen.md).

**The volume control that "worked" did nothing.** `AUDIO_VOLUME_SET` (the typed `setVolume`) returns 0 for any value
and changes nothing on this car, and with no state to read back the app reported success. The car's volume is
Android's media stream (`AudioManager` stream 3, 0..39; `cmd media_session volume --stream 3 --get/--set`), and
`AUDIO_MASTER_VOLUME_STATE` and `AUDIO_VOLUME` follow it. The registry entry now sets the stream and reads it
back. Any control that is only "accepted by the HAL" with no read-back is unproven in the same way.

**Large vendor apps are not a source of ids.** One app's 467 MB package was assets; its dex held no raw ids.

**Polling cannot see short pulses** (see [research-method.md](research-method.md#pitfalls)).

## Open questions

- Air-direction value `7` and the temperature-sync property (`110`, only ever seen as `0`) are unexplained.
- Ambient lighting: the brightness state ranges 1–6 and the colour 64–105 on this car, but the controls have not
  been identified; the registry's entry for it is unverified.
- Whether other models expose the same ids. Contributions that confirm or contradict a row of
  [vehicle-commands.md](vehicle-commands.md) on another model are the most useful thing you can send.
