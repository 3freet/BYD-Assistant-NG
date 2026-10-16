# The instrument cluster screen

How an app ends up on the driver's instrument cluster (the gauge display behind the steering wheel), what it
needs, and what did not work. Observed on one car (a Fangchengbao Leopard 5, DiLink 5.1, Android 13) while it was
parked; treat every id as a hypothesis until you have read it back on your own vehicle.

The assistant has a `cluster_screen` tool built on this, **in debug (development) builds only**: it switches
BYD's navigation app on and off, which no release should do behind the user's back.

## What the cluster is fed from

`com.xdja.containerservice` (uid 1000) publishes three virtual displays, all 1920×720 with the layer stack equal
to the display id:

| Display | Name | Observed |
| --- | --- | --- |
| 3 | `fission_bg_XDJAScreenProjection` | the one the cluster shows when it is on a map view |
| 4, 5 | `shared_fission_bg_XDJAScreenProjection_0` / `_1` | accept activities, nothing reaches the cluster |

An activity started on any of them with `am start --display <n>` is accepted and runs there. **That alone puts
nothing on the cluster.** The cluster only shows display 3 while the car is told its navigation view is "on the
cluster", and the navigation app's own meter activity is the thing that owns display 3 at that moment.

## What works

All of it runs as the ADB shell identity (the same helper process the vehicle commands use):

1. **BYD Maps (`com.byd.naviauto`) must be enabled.** It is disabled on this car out of the box. Package-level
   `pm enable --user 0 com.byd.naviauto` works for the shell; the component-level form is refused ("Shell cannot
   change component state"). Wait until `cmd package query-activities -a com.byd.naviauto.START_MAP_VIEW` finds an
   activity, or the next step starts nothing.
2. **Write the navigation type.** Instrument device (type 1007), control `INSTRUMENT_NAVI_TYPE_SET`
   (id 1276157976), state id 1086337074. `1` is the normal gauges, `4` is "navigation on the cluster". Read the
   state back: it is the only proof the car took it.
3. **Start the navigation's meter activity on display 3**: `am start-activity --display 3 -f 0x18000000 -n
   com.byd.naviauto/com.byd.automap.meter.MeterActivity`. The cluster now shows the map view's frame.
4. **Start the wanted app on display 3**: `am start-activity --user 0 --display 3 --windowingMode 1
   --activity-multiple-task -f 0x10000000 -n <component>`. The cluster shows it in place of the gauges.
5. **Settle.** A navigation app that was not already running puts its meter back in front three to five seconds
   after step 3. Poll the top activity of display 3 and start the app again until it holds for several seconds
   (about nine seconds in total on this car). Re-write type `4` on each poll; the car resets it when the
   navigation app starts.

**Moving instead of starting.** An app that is already running on the main screen is put on the cluster with
`am display move-stack <root task id> 3` (the task id comes from `dumpsys activity activities`; records with
`sz=0` have no activity left and are refused: "There is no running activity in rootTask"). The task keeps its
process and state. Starting the app on display 3 instead makes a second copy, and a second copy of a media app
stops what the first was playing.

**The meter answers back.** The navigation app answers our meter start by bringing its own meter to the front once
more, 0.3-1.5 s later (seen warm and cold; it is a reaction to the intent, the navigation app does not start the
meter by itself when only the map type is written). An app that is in front at that moment is covered for a
fraction of a second. A video that is covered goes into a picture-in-picture corner (`mode=pinned`) and stops.
So: an app that is not playing is put on the cluster at once, and a keeper thread brings it back to the front
if it is covered (`am start-activity --user 0 --display 3 -f 0x10020000 -n <launcher activity>`, no
`--activity-multiple-task`: the task that is there is brought forward, "its current task has been brought to the
front"); an app that is playing, or a video link, waits 2.5 s after the meter start first.

**A moved player stops and shrinks.** Moving YouTube between displays (1920x720 against 2560x1440) makes its
watch page start again: the media session goes to STOPPED a moment later and the page comes back as the in-app
miniplayer (`watch_player` 384x216 in the bottom corner of the window, read from `dumpsys activity <hash>`, the
hash being the display's `topResumedActivity`). Pressing the miniplayer on the cluster works with
`input -d 3 tap <x> <y>` (the display is addressable), and `cmd media_session dispatch play` starts the video
again (from its beginning). Both are done by the keeper, only for a player that was playing before the move.

**A link.** `am start-activity --user 0 --display 3 --windowingMode 1 --activity-multiple-task -f 0x10000000
-a android.intent.action.VIEW -d <https link> -p <package>`, after the 2.5 s. YouTube's link handler
starts its real screen on the main display whatever was asked (the passenger screen does the same), so when the
app is not on display 3 shortly after, its task is moved with `move-stack` as above. Which video a phrase means
is found by the app, not the car: the first `videoRenderer` of the results page for the phrase (the page the
app's own search link opens), and the watch link of that video is what is opened.

**Closing** is the other way round, and the order matters, because disabling BYD Maps while an app is still on
the cluster left the app stuck there: write type `1` first (the navigation app is still there to hand the picture
back), force-stop the apps on display 3, wait until only the meter is left and give the cluster a moment (about
1.2 s), force-stop the navigation app, wait until display 3 has no task at all, check the type reads `1`, and only
then `pm disable-user` BYD Maps (unless it should stay on). If something is still on the display, BYD Maps is
left enabled and the answer says so. Do **not** force-stop the navigation app *before* writing type `4` when
opening: the car then resets the type to `1` a moment later and the app lands on a dark cluster.

**Why a player stops on the cluster and not on the passenger screen.** Moving YouTube main to FSE (by
`move-stack` or by `am start --display 2`) keeps the same activity record and the same playback, with no state
change at all. Moving it to the cluster keeps the record too, but its media session goes STOPPED, then BUFFERING,
then PLAYING about one to two seconds later, a few seconds further back. What the app sees on each display:

| Display | Configuration seen by the app |
| --- | --- |
| main (0) | `sw720dp w1280dp h604dp 320dpi lrg land finger` |
| passenger (2) | `sw480dp w1176dp h480dp 240dpi lrg land finger` |
| cluster (3) | `sw360dp w960dp h360dp 320dpi nrml land -touch` |

The cluster is the only one that is a normal-size screen with no touchscreen. Setting its density to 240
(`wm density 240 -d 3`, reset with `wm density reset -d 3`) made the size class `lrg` and the width 1280 dp like
the others and changed nothing, so what remains is the missing touchscreen. It cannot be given one. The player
restarts itself on the way in; the keeper only makes sure of it.

**Leaving the cluster is a rule, not a command.** Whatever takes the app off the cluster (the main screen's own
"open" of the same app, which relocates its task to display 0; the passenger screen; a close; the app ending), the
helper notices that the app has no activity on display 3 any more (twice in a row, 0.7 s apart) and runs the
sequence above on its own: type `1`, nav stopped, BYD Maps disabled when the user chose that, within about two
seconds. The relocation by the main screen's "open" also makes a player stop (state STOPPED) a moment after the
move; the same watcher starts it again with `cmd media_session dispatch play` (and presses the miniplayer if the
page came back as one), for a player that was playing while it was on the cluster. Verified with a playing YouTube:
STOPPED at 3.9 s, PLAYING again one second later, on the main display with the large player, gauges back.

Between the app going and the watcher noticing, the navigation app's map shows on the cluster for a moment. Two
things close that gap: the tools that open an app elsewhere (the main screen's open, the passenger screen's)
first tell the helper (`cluster leaving <package>`), which writes map type `1` before the app is moved, so the
gauges are already back when it goes; and the watcher polls every 0.4 s and acts at once when it finds the app's
task on another display.

Timings from the car: a started app or a moved app that is not playing is answered in under a second (the helper
answers as soon as the app is in front); a moved player or a link, about 3 s; close 2 s. Yandex Navi and YouTube
were tried; the car's own gauges came back after close each time.

## When YouTube crashes on a move

The YouTube on this head unit is a patched build (the Morphe patches). Moving it between displays makes Android
relaunch its activity, because the cluster's touchscreen, screen layout and size differ from the main screen's
(`relaunch … changes = 3336` in the system log), and on some runs the new activity's `onCreate` throws a
`ClassNotFoundException` for `com.google.android.gms.org.conscrypt.OpenSSLRSAPublicKey` out of the patch's
package-info hook (`SpoofUtil`). That is the patched app's own bug, and nothing here works around it: if the app is
gone after a move, the cluster gets its gauges back (the leave rule above) and the app has to be asked for again.
The same hook crashes the app a few seconds after some cold starts. Our close force-stops the app, so the next
start is a cold one: a test that force-stops it between runs sees the crash far more often than one that leaves
it running. Look in `logcat -b crash`, or the crash files the patch writes to `Documents/Morphe`, before blaming
the move.

## Side effects to know about

- **Enabled BYD Maps changes the main screen.** While it is enabled, the home screen's background switcher
  cycles through the map as one of its entries (and the instrument cluster toggle lives inside it). That is why
  the tool keeps the package enabled only while an app is on the cluster, and disables it again on close. A
  setting lets the user keep it enabled instead.
- **The tool assumes Maps is disabled** at the start; step 1 is always "enable it".
- Which display is the cluster is looked up by name (`fission_bg_` prefix), not by a fixed id.
- The helper is also told never to stop system packages and never offers `com.byd.naviauto` itself as an app.

## What did not work

- Starting the app on displays 3, 4 or 5 with BYD Maps disabled, or enabled but never put on the cluster: runs,
  not shown.
- Sending cluster commands through the car's `com.byd.clusterdebug` app (numbered `AutoContainerManager.sendInfo`
  commands, e.g. 278 and 279): no visible effect, in either gauge view. Its broadcast receiver can inject raw
  frames to the car's own network; it is exported and unsafe, and nothing here sends to it.
- Writing the typed gauge switches (`setViewSwitch`, view status): accepted, nothing changes. See
  [findings-and-dead-ends.md](findings-and-dead-ends.md) for the normal / sport gauge switch.
- Sending as the shell to the container service: its allow-list (`/system/etc/container_comm_cfg.json`) names
  packages, and the shell is not among them.

## Not verified

- Anything while driving. The car was parked and stationary throughout; do not use this on a moving car.
- Other apps than Yandex Navi and YouTube, other models, other firmware.
- The keeper's expand-and-play of a moved video, end to end: the test YouTube crashed a few seconds after the
  video started, every time (see above), before the keeper had run. The pieces were each observed by hand
  (the tap expands the miniplayer, the media key starts the video again).
