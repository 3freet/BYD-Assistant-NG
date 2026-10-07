# How to find a control and learn what its values mean

This is the workflow every control in [vehicle-commands.md](vehicle-commands.md) went through. It needs a
computer with `adb`, the Android SDK (a platform jar and build-tools for `d8`), a JDK, and **a parked car**.

## 0. Ground rules

- Be **parked**, with someone else responsible for the car if you are not alone. Never experiment with anything
  that affects driving, braking, steering assistance, the powertrain or the security system.
- Start with **reading only**. Work out what a control does by watching the car's own screens before you ever
  write to it.
- Write to a control only when you intend the effect, restore what you changed, and **read the state back**
  every time.

## 1. Connect

Switch on ADB debugging on the head unit (`adb connect <ip>:5555` over the network the unit is joined to).
Phone-hotspot links drop without warning; see "Record on the device" below.

## 2. The probes

[`tools/probes`](../../tools/probes) holds three small read-only programs. `build.sh` compiles them with
`javac --release 8`, converts them with `d8` and pushes `probes.dex` to `/data/local/tmp`. They run as the
shell user, which the vehicle services accept:

```bash
ANDROID_HOME=… JAVA_HOME=… tools/probes/build.sh
adb shell "CLASSPATH=/data/local/tmp/probes.dex app_process /system/bin <Probe> <arguments>"
```

| Probe | Does | Example |
| --- | --- | --- |
| `Feat <regex> [read]` | Lists the ids in `BYDAutoFeatureIds` whose names match, and with `read` the value each state holds right now | `Feat 'FRIDGE' read` |
| `Members <class> <regex>` | Lists a class's constants (with values) and method signatures; never calls anything | `Members android.hardware.bydauto.security.BYDAutoSecurityDevice '.'` |
| `Watch <regex> <seconds> [sweepMs]` | Polls every readable state (about 3,700 across the main devices) and prints each change with a time | `Watch . 240 0` |

None of them can write: they contain no call to `set`. Remove the dex afterwards
(`adb shell rm /data/local/tmp/probes.dex`).

## 3. Record while you operate the native screen

1. Start `Watch . <seconds> 0`. It first spends five seconds finding the states that change by themselves
   (clocks, animated lights) and drops them, then prints `HH:mm:ss.SSS  Device.NAME  old -> new  (id N)`.
2. On the car's own screen, operate **one control at a time**, pausing a couple of seconds between actions so the
   log separates them.
3. Read the log. Each toggle shows up as the state ids it changed, and the old → new values are the meaning of
   the control's values. Several ids often change together (a mode and a level, a switch and its status).

Work with a quiet car: while driving, pitch, roll, steering and indicator ids dominate the output.

## 4. Use the system log as well

`Watch` only sees states it polls (about every 130 ms, and only the main devices). Two sources in `logcat`
see more:

- Every vehicle device logs its events as `postEvent device_type: N, event_type =HEX, value = V`. `event_type`
  is the state id in hex, for **all** devices, and it catches pulses far shorter than the polling interval.
- The A/C service logs `PropertyValue{mId=…, mArea=…, mValue=…}` for each property change (see
  [ac-service.md](ac-service.md)) — the quickest way to learn an A/C control.

## 5. Record on the device, not over the link

A streamed recording dies when the network link does — and with it the one-off sequence you just asked the
driver to repeat. Start the recorders **on the head unit**, detached, and pull the files afterwards:

```bash
adb shell 'setsid nohup sh -c "CLASSPATH=/data/local/tmp/probes.dex exec app_process /system/bin Watch . 170 0" \
  > /data/local/tmp/r_watch.txt 2>&1 < /dev/null &
setsid nohup timeout 185 logcat -b all -T 1 -v threadtime -f /data/local/tmp/r_logcat.txt < /dev/null > /dev/null 2>&1 &'
# ...operate the car, then:
adb pull /data/local/tmp/r_watch.txt ; adb pull /data/local/tmp/r_logcat.txt
adb shell rm /data/local/tmp/r_watch.txt /data/local/tmp/r_logcat.txt
```

Check with `ps -A | grep -E "app_process|logcat"` that they are running before you start pressing things.

## 6. Find the control that matches

The state you saw change is `X`; the control is usually `X_SET`. Search with `Feat 'X' read` (the regex is case
insensitive). If there is no `X_SET`, look for similar names — the massage intensity control is called
`…WORK_INTENSUTY_SET` (sic) while its state is `…MASSAGE_LEVEL`, so searching for `LEVEL_SET` finds nothing.
For the A/C use the property ids from the service log instead.

## 7. Verify by writing once, and reading back

When you do write: one control, one value, then read the state until it changes (a second or two), compare it
with what you intended, and **restore** what it was before. Snapshot the related states first so you can prove
the restoration. The app's own `vehicle_commands.json` entries carry a `stateFeatureId` for this reason.

## 8. Decompiling

Two things are worth opening in a decompiler such as jadx:

- `/system/framework/com.byd.ac.jar` — small, readable, and names every A/C property id.
- The framework itself (`framework.jar`, split over several dex files): `strings` over the dex lists every
  `android/hardware/bydauto/<name>/BYDAuto<Name>Device` class. Large vendor apps are mostly assets and
  often contain no useful ids — one such app's 467 MB package held nothing beyond its own screens.

## Pitfalls

- A control can accept a value and ignore it (massage intensity `0`), or accept it only in the right state (a
  fridge temperature is refused unless the fridge is already cooling). Only the read-back tells.
- Signals are not always what the screen shows: the fridge's cooling set-point is the screen value **+ 19**.
- A `get` that returns `-10011` or `65535` means "not fitted / not available" — a useful way to find out what
  your car lacks (rear massage on the unit this was built on).
- The polling probe can **miss pulses shorter than its sweep**. The wheel pad flexing when the horn is pressed
  produces a ~10 ms pulse that only the system log shows.
