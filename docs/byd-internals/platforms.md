# DiLink platforms: which numbers the car uses

> As everywhere in these notes: every number for DiLink 5.1 was observed on one vehicle (a Fangchengbao Leopard 5).
> **Nothing here has been observed on a DiLink 3 or DiLink 4 car.** What the app does there is unconfirmed until someone
> reads it back on their own car (see [confirming a car](#confirming-a-car)).

## The short version

- The car's framework class `android.hardware.bydauto.BYDAutoFeatureIds` hands out the number of every feature, and
  the numbers are **not the same on every car** (see [where the numbers come from](#where-the-numbers-come-from)). The
  class picks them itself when it loads, from the car's bus protocol, so DiLink 3 cars are expected to number many
  features differently from DiLink 5 ones, with some features missing. The same number can mean a different window on
  one car than on another.
- **The Android version cannot tell the generations apart.** DiLink 3 and DiLink 4 both run Android 10, DiLink 5.0
  runs Android 12 and DiLink 5.1 runs Android 13.
- **So the app asks the car, by name.** Every number in the command registry has the name the car's own table gives it
  (`Bodywork.BODYWORK_LF_WINDOW_CTRL_SET`). When the vehicle helper starts it reads that table, and each command takes **the
  car's number** for each of its names. A command whose control the car does not name is **withheld**: not offered to the
  model and never sent, because a number the car does not name is not known to be that control on that car, and it may be
  another feature's. On the car the numbers were verified on, the table agrees everywhere and nothing changes
  (`names=76/76`).
- The platform (DiLink 3, 4 or 5) is still read, for the log and to decide how careful to be: a window's percent control
  is not sent on a car that is not a recognised DiLink 5 unless the car reports the window's position. It does not
  choose numbers.
- When the car's table cannot be read, the registry's own numbers are used, as they always were.
- **Cars of one generation can differ too,** and so can two firmware versions of the same car; that is why the numbers come
  from each car's own table and not from a table kept per platform.

## Where the numbers come from

`BYDAutoFeatureIds` is not a table of fixed constants. Reading the class as it is in our car's own framework (decompiled
from the `framework.jar` pulled from it): when it loads, it reads the system properties `sys.car.protocol` and
`persist.sys.protocol.record`, and sets two flags from them: `isCanFD` (the value is `CANFD`) and `isToyota` (the value is
`TOYOTA`). Of the 3,219 constants of its top-level class, 2,291 are then computed from those flags, each one of three
numbers: the CAN-FD one, the Toyota one, or, when neither flag is set, the classic CAN one. The other 928 are fixed. For
example `AC_CTRL_MODE` is 1077936146 on CAN-FD, 801112082 on Toyota and 330301458 on classic CAN.

Our car (DiLink 5.1) has `sys.car.protocol=CANFD` (`persist.sys.protocol.record` is empty) and the CAN-FD numbers, which
are the "DiLink 4 and 5" column of the table below. The "DiLink 3" column is the classic CAN scheme. So what the three
signature features measure is the numbering of the car's bus protocol, and that DiLink 3 cars are classic CAN and DiLink 4
cars CAN-FD is only an expectation until such a car says what its property holds. Not every constant has three numbers:
`BODYWORK_LF_WINDOW_CTRL_SET`, for one, is 589299768 on Toyota and 1125122104 on both of the others, so the window
controls in the registry are right on CAN-FD and classic CAN and not on a Toyota-protocol car. A Toyota-protocol car
matches neither scheme in the table below and is not recognised. Another firmware version of the class may choose
differently again, so two cars on the same protocol can still differ: the app takes its numbers from each car's own table
by name for that reason (see [how a command finds its numbers](#how-a-command-finds-its-numbers)).

## How the platform is found

1. **The car's own numbers.** Three features whose number is expected to tell the schemes apart are read from the
   car's own table (`PlatformSignature`):

   | Feature (`Ac` group) | DiLink 3 | DiLink 4 and 5 |
   | --- | --- | --- |
   | `AC_CTRL_MODE` | 330301458 | 1077936146 |
   | `AC_CYCLE_MODE` | 330301460 | 1077936148 |
   | `AC_DEFROST_FRONT_STATE` | 330301462 | 1077936150 |

   All three must agree with one scheme. A mix, a missing feature, or numbers that match neither scheme is **not
   recognised**. That changes only how careful the app is (see below), not which numbers it uses.
2. **The Android level**, which splits DiLink 4 (Android 10, SDK 29 or lower) from DiLink 5 once the numbers say "4 or
   5".

How they combine: DiLink 3 numbers → **DiLink 3**, at any Android level. DiLink 4/5 numbers → **DiLink 4** on Android 10
or lower, otherwise **DiLink 5**. Numbers that no known scheme uses, or that cannot be read, leave the platform not
recognised.

**The head unit's properties do not decide it.** `ro.vehicle.type`, `ro.byd.dilink.version`, `ro.dilink.version` and
`ro.product.model` are read through `android.os.SystemProperties` and written to the log, so that a car's
identification can be read from its owner's log. The properties that hold a VIN, the car's name or its region are not
read. On our car (a Leopard 5):

| Property | Value |
| --- | --- |
| `ro.vehicle.type` | `Di5.1_5.0UI` |
| `ro.product.model` | `DiLink5.1` |
| `ro.byd.dilink.version`, `ro.dilink.version` | not set |

`ro.vehicle.type` reads `<platform>_<interface>`: this car is the `Di5.1` platform showing the `5.0UI` interface, so the
two generations are not the same thing and a unit can show an interface from another generation than its platform's.
What it holds on DiLink 3 and 4 cars is not known: the log says (see [the log line](#the-log-line)). The numbers stay
what decides, because they are what the app sends.

The **vehicle helper** (the process that runs as the ADB shell and makes the actual calls) does the reading itself and
tells the app in its ready line. Its reading is what its own commands are checked against; the app uses it to decide
which commands to offer the model. The app also tries when it starts, and keeps the helper's last answer (for the same
firmware) for the first conversation after an update.

## How a command finds its numbers

Each entry in `app/src/main/resources/vehicle_commands.json` is a command with the numbers it was verified with on a DiLink 5
car, and with the name the car's own table gives each of them: `featureName` for the control, `stateFeatureName` for the state
that reports the result, and the same on a `requires` (a state that must hold first) and on each of `overrides` (another
control sent for one particular value). Three things are decided when the vehicle helper starts, by `NameResolution`:

| Name | If the car's table has it | If it does not |
| --- | --- | --- |
| the control | the car's number is used | the command is **withheld** |
| a `requires` | the car's number is used | the command is **withheld** (its precondition cannot be checked) |
| an override's control | the car's number is used | the command is **withheld** (the one value that needs it is one of the command's values) |
| the state | the car's number is used to confirm the result | the command is offered **without a read-back**: it is sent and the answer is "sent", not a verdict from a number that may mean something else |
| the state of a command that **insists on a reading** | the car's number is used | the command is **withheld** |

A command that insists on a reading is one of the four window percent controls (`requiresReadbackElsewhere` in the file): on
a car that is not a recognised DiLink 5 nothing is sent unless the car answers a read of the window's position first, since
a control the car names may still not be wired to anything. A typed command (`ambient.color`, `ambient.brightness`) is the
car's own method, so only its state's name matters. The A/C, the dashcam and the volume use no feature numbers of the car's
table and are never withheld.

The helper tells the app which commands the car cannot do in its ready line (`unavailable=`), because only the helper can read
the table. The app takes those out of what it offers the model, and keeps the list, with the platform, for this firmware, so
that the first conversation after a restart is already right. Whatever the app offers, the helper checks again when a command
arrives and answers "does not have a control for that" for one the car cannot do.

The reads the app makes outside the registry (the headrest sound, whether the driver's seat is occupied and the door shut,
the cabin temperature) take the car's number for their names the same way, and give no reading where the car lacks the name.
The three features that **write** to the car and were verified on one car only (the drive mode, the exterior speaker, the
instrument cluster) are different: each stays off unless the car's table gives its name exactly the verified number
(`isOnThisCar`), since each is guarded by a check of the exact id.

### Windows on a car that is not a recognised DiLink 5

None of this has been seen by us on such a car; all of it is possible:

- **The control's number is not the car's.** This is what the lookup by name fixes: the open/close/stop/half/breath
  controls take the car's own number. The log says what they were (`differ=`).
- **No working percent control.** A read of the window position answers "not wired" and only the open/close/stop/half/breath
  control exists. `window.<door>.position` is refused up front on such a car, instead of being sent to a control that is not
  there.
- **An open/close control that is accepted and does not move the glass,** while the percent control works. The app does
  not notice that: an `open` there is reported as sent, and the window stays where it is. The percent commands
  (`window.<door>.position`) would be the ones that work.

Which of these a car does is told by its log and by trying, not by its version.

## The log line

The first time the vehicle helper starts in a run of the app (and again if the platform it finds, or what the car cannot do,
changes), the log gets one `Vehicle helper found DiLink 3: …` line, written by `CarDiagnostics`. Writing it only looks;
nothing is written to the car:

| Field | What it says |
| --- | --- |
| `sdk`, `release` | The Android level and version. |
| `props` | The head unit's own identification (`vehicle.type`, `byd.dilink.version`, `dilink.version`, `product.model`); `-` where one is not set. Logged only; it does not decide anything. |
| `numbers` | The car's numbers for the three signature features (`?` where the car has none). |
| `constants` | How many numbers the car's table holds, which differs between firmware versions. |
| `classes` | Which device classes exist; a leading `-` means missing. |
| `calls` | Where the generic `get`/`set` calls are found (or `missing`). |
| `services` | Whether the `autoservice` and `byd_airconditioning` services answer; a leading `-` means no. |
| `acroute` | How the A/C is reached: `service` (the `byd_airconditioning` service, as on DiLink 5.1), `device` (the car has no such service but has the climate device class, and the A/C commands call its methods) or `none` (neither: the A/C commands are not offered). See [ac-service.md](ac-service.md#a-car-without-the-service). |
| `acabsent=<name>,…` | Only with `acroute=device`: the names the device route reads back that the car's table lacks. The command is still sent and answers "sent" without a confirmation. |
| `perms=<name>:<level>/<helper>/<app>,…` | For each of the `BYDAUTO_<DEVICE>_COMMON`, `_GET` and `_SET` permissions of seven devices: how the system defines it (`n` normal, `d` dangerous, `s` signature, `S` signature or system, then `p` privileged and `v` development; `-` not defined), whether the helper (the ADB shell) holds it (`y`/`n`) and whether the app does. Shows whether a car refuses the helper for want of a permission. |
| `others=<service>,…` | Only with `acroute=device` or `none`: the car's other services whose names look like the climate or the vehicle's own systems, to find where a climate system without the usual service lives. Names only; `?` where the service manager could not be asked. |
| `names=<agree>/<checked>` | How many of the distinct names the app uses (the registry's and the few outside it, in `OtherFeatures`) have, in the car's table, the number the app has for them. A name used by several commands counts once. `names=?` when the car's table could not be read (the registry's own numbers are then in use). On our car: `names=76/76`. |
| `differ=<name>=<car's>/<ours>(<command>)` | The car's table has the name under **another number** (the car's first, ours second). The car's is the one used. |
| `absent=<name>(<command>)` | The car's table has **no such name**. What that costs the command is in the table above. |
| `withheld=<id>,…` | The commands this car cannot do because of it (`none` for none). |
| `windows` | Only when a window command's name does not have the number the app has for it: every window feature in the car's table with its number. |

Each list is cut after 150 entries (`,+N more`), so a car that differs everywhere is still one line.

The app log is under *Settings → App → View app log*, where the log can be shared or saved with what is private in it
hidden (what you said, places, keys, addresses and numbers). The log may have run for days and lost the lines at its start, so
**the header of every exported log repeats this line** (as the helper last wrote it, with the time), or says that the helper
has not started on this firmware yet, which is itself worth knowing: the helper does not start when ADB debugging is not
allowed on the head unit.

## Confirming a car

1. Get the owner's saved log (*Settings → App → View app log → Save to Downloads*, with *Hide private info* on). The header
   and the `Vehicle helper found …` line say what the car's table holds and what the app made of it, and the `dispatch …` lines
   say what each command did.
2. Operate the control on the car's own screen and watch which state changes, as for any control, and compare: a control
   the log reports as "sent" with no confirmation is confirmed only by the owner saying the glass moved.
3. Where a car's own table has no name for something the car does have under another name, add the name to the registry
   (`featureName`) and say so here. The car's numbers themselves are never entered by hand.

## Status

- **DiLink 5.1:** numbers observed on one Fangchengbao Leopard 5 (Android 13, software 34.1.3.2512224.1, CAN-FD). The
  lookup by name reads `names=76/76` on it, so every command keeps the number it always had; its table holds 13,823
  numbers, all five device classes the app uses exist, the generic `get` and `set` are found on `AbsBYDAutoDevice`, and both
  the `autoservice` and `byd_airconditioning` services answer.
- **DiLink 5.0:** known from one owner's log (a BYD Song Plus, Android 12, beta 1.2.0-beta.2), not observed by us. The helper
  starts and reads `DiLink 5` (the same CAN-FD numbers on the three signature features); `vehicle.type` is `Di5.0_5.0UI`; the
  table holds 14,851 numbers, all five device classes exist, the generic `get` and `set` are found on `AbsBYDAutoDevice` and
  the `autoservice` answers. `names=70/76`: the windows, seats and the other controls agree with our numbers; the car numbers the
  headrest sound control differently (its number is used) and has no names for the Devialet space-sound controls
  (`audio.spaceSound` and `audio.spaceSoundMode` are not offered) or for the headrest sound's state. **The car refuses the
  helper, though**: its climate, bodywork, setting and instrument classes want a runtime permission
  (`BYDAUTO_<DEVICE>_COMMON`) that the ADB shell cannot hold, so every window, seat, light, drive-mode and temperature command
  failed with "Neither user 2000 nor current process has android.permission.BYDAUTO_BODYWORK_COMMON."
  ([hal-and-permissions.md](hal-and-permissions.md#dilink-50-the-device-classes-ask-for-a-runtime-permission)). Reading the tyre
  pressure, which has no such permission, worked. The car also has no `byd_airconditioning` service; where it is missing the
  A/C commands now go through the climate device class
  ([ac-service.md](ac-service.md#a-car-without-the-service)), which that car refuses for the same reason. So on that car the
  assistant, apps, navigation, media and the tyre pressure work, and the car's controls do not yet.
- **Other cars:** not observed by us. The lookup by name was run in a simulator built from our car's own framework, with the
  class under the classic CAN and under the Toyota numbering: each time the commands were sent to the car's numbers. That a
  table without a name withholds the command is covered by unit tests. It has not been run on a DiLink 3 or DiLink 4 car.
