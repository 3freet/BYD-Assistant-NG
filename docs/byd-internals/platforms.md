# DiLink platforms: which numbers the car uses

> As everywhere in these notes: every number for DiLink 5.1 was observed on one vehicle (a Fangchengbao Leopard 5).
> **Nothing here has been observed by us on a DiLink 3 or DiLink 4 car**; one DiLink 3.0 car is known from its owner's log
> (see [the status](#status)). What the app does elsewhere is unconfirmed until someone reads it back on their own car
> (see [confirming a car](#confirming-a-car)).

## The short version

- The car's framework class `android.hardware.bydauto.BYDAutoFeatureIds` hands out the number of every feature, and
  the numbers are **not the same on every car** (see [where the numbers come from](#where-the-numbers-come-from)). The
  class picks them itself when it loads, from the car's bus protocol, so DiLink 3 cars are expected to number many
  features differently from DiLink 5 ones, with some features missing. The same number can mean a different window on
  one car than on another. (A DiLink 3.0 head unit has since been seen with the CAN-FD numbers of DiLink 5, so the
  generation does not decide the numbers; the car's own table does.)
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
signature features measure is the numbering of the car's bus protocol. That DiLink 3 cars are classic CAN and DiLink 4
cars CAN-FD did not hold for the one DiLink 3.0 car seen (`Di3.0_3.5UI`, Android 10): it has the CAN-FD numbers. Not every
constant has three numbers:
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

That reading calls the DiLink 3.0 car above a DiLink 4 one (CAN-FD numbers, Android 10). The log therefore names a car by its
own platform where its `ro.vehicle.type` writes one as a version (`Di3.0` in `Di3.0_3.5UI`) and keeps the other reading in
brackets: `Vehicle helper found DiLink 3.0 (numbers read as DiLink 4)`. Only the words change. What the app does follows the
numbers and the Android level, and a property that is not written as a version (a unit that holds `DiLink50_5.0UI`) is
left out of the name as before.

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
On a DiLink 3.0 car (Android 10, from its owner's log) `ro.vehicle.type` is `Di3.0_3.5UI` and `ro.product.model` is
`DiLink3.0_For_BYD_AUTO`; the two version properties are not set. What it holds on DiLink 4 cars is not known: the log says
(see [the log line](#the-log-line)). The numbers stay what decides, because they are what the app sends.

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
(`isOnThisCar`), since each is guarded by a check of the exact id. The one exception to that is the drive mode's second route (the energy
device's operation mode, for a car that does not have the first): its feature is found by name in the car's table and used with the number the
car gives it, and what guards it is the fixed list of six values, the speed limit and the read-back.

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
| `classes` | Which device classes exist: the ones the commands go through (`bodywork`, `light`, `audio`, `setting`, `ac`) and the two the drive mode goes through (`energy`, `speed`); a leading `-` means missing. |
| `calls` | Where the generic `get`/`set` calls are found (or `missing`). |
| `services` | Whether the `autoservice` and `byd_airconditioning` services answer; a leading `-` means no. |
| `acroute` | How the A/C is reached: `service` (the `byd_airconditioning` service, as on DiLink 5.1), `device` (the car has no such service but has the climate device class, and the A/C commands call its methods) or `none` (neither: the A/C commands are not offered). See [ac-service.md](ac-service.md#a-car-without-the-service). |
| `acabsent=<name>,…` | Only with `acroute=device`: the names the device route reads back that the car's table lacks. The command is still sent and answers "sent" without a confirmation. |
| `perms=<name>:<level>/<helper>/<app>,…` | For each of the `BYDAUTO_<DEVICE>_COMMON`, `_GET` and `_SET` permissions of nine devices (the seven the commands reach, and `SPEED` and `ENERGY`, which the drive mode goes through): how the system defines it (`n` normal, `d` dangerous, `s` signature, `S` signature or system, then `p` privileged and `v` development; `-` not defined), whether the helper (the ADB shell) holds it (`y`/`n`) and whether the app does. Shows whether a car refuses the helper for want of a permission. |
| `native=reads:<read>/<tried>,agree:<same>/<compared>,ms:<n>` | Whether the car's native service can be spoken to directly, how many of about a dozen states the commands read back it answered, how many of those it answers like the device class (where the car lets the helper use it), and how long this check took in milliseconds (it runs once, as the helper starts). `-` where the car has no such service. See [the native route](hal-and-permissions.md#the-native-route). |
| `acalias=<descriptor>`, `acservice=<name>` | The climate service under its other name, `airconditioning`, with the interface its binder says it speaks (the commands use it only if that is `com.byd.ac.IBydAcService`); `acservice=` appears when that name is the one in use. |
| `others=<service>,…` | Only with `acroute=device` or `none`: the car's other services whose names look like the climate or the vehicle's own systems, to find where a climate system without the usual service lives. Names only; `?` where the service manager could not be asked. |
| `names=<agree>/<checked>` | How many of the distinct names the app uses (the registry's and the few outside it, in `OtherFeatures`) have, in the car's table, the number the app has for them. A name used by several commands counts once. `names=?` when the car's table could not be read (the registry's own numbers are then in use). On our car: `names=76/76`. |
| `differ=<name>=<car's>/<ours>(<command>)` | The car's table has the name under **another number** (the car's first, ours second). The car's is the one used. |
| `absent=<name>(<command>)` | The car's table has **no such name**. What that costs the command is in the table above. |
| `withheld=<id>,…` | The commands this car cannot do because of it (`none` for none). |
| `windows` | Only when a window command's name does not have the number the app has for it: every window feature in the car's table with its number. |
| `similar=<missing name>:<name>=<number>\|…,…` | For the names the car lacks (`absent=`), up to three names of the car's own table that share the words that say what the name is about, each with the car's number, to find a feature that sits under another name. The features the app can offer come first; at most 24 names are looked up (`,+N not looked up`). Only names and numbers of the car's table are written, and `_hal_only` copies are left out. |
| `scan.drive=…`, `scan.speaker=…` | Only when the drive mode (`SETTING_PRESELECTED_DRIVING_MODE_SET`) or the exterior speaker (`ADAS_UE_BROADCAST_POSITION_SET`) is not in the car's table under the verified number: up to 40 names of the table that have to do with driving modes (`DRIVING_MODE`, `ENERGY_OPERATION_MODE`, `ECO_MODE`, …) or with the exterior speaker (`EXTERIOR_SPEAKER`, `AVAS`, `UE_BROADCAST`, `LOCK_CAR_SOUND`, …), with their numbers (`,+N more`). Seat massage modes, faults, prompts and factory-test entries are left out. A car that has both gets neither field. |
| `peek=<name>:<number>,…` | The numbers a few states hold, read only and straight from the car's native service: the exterior speaker's configuration, switch status and source states (`AUDIO_EXTERIOR_SPEAKER_CONFIG`, `_SWITCH_STATUS`, `AUDIO_AVAS_AUDIO_SOURCE_TO_EXTERNAL_SPEAKER_STATUS`, …), the energy operation mode and its terrain setting (`ENERGY_ROAD_SURFACE_MODE`) and the drive mode states, for the names the car's table has (`?` where the car gave no answer). Nothing is assumed about what they mean: an owner's log taken before and after the owner moves the car's own setting shows which one moves. `-10011` is the car's own "not available" and `65535` its "no answer". |
| `speed=<result>` | What reading the car's speed through its Speed device class gave when the helper started: `ok:<km/h>`, `none` (the car answered without a number), `refused:<permission>` (the car's permission check refused the helper), `missing:<name>` (the class or a method is not on this car) or `failed:<exception>`. The drive mode's 80 km/h gate needs this read, and where it gives no number nothing is sent, so this says why the drive mode does not go on a car whose other names are all there. Read only. |

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
- **DiLink 5.0:** known from one owner's logs (a BYD Song Plus, Android 12, betas 2 and 3), not observed by us. The helper
  starts and reads `DiLink 5` (the same CAN-FD numbers on the three signature features); `vehicle.type` is `Di5.0_5.0UI`; the
  table holds 14,851 numbers, all five device classes exist, the generic `get` and `set` are found on `AbsBYDAutoDevice` and
  the `autoservice` answers. `names=70/76`: the windows, seats and the other controls agree with our numbers; the car numbers the
  headrest sound control differently (its number is used), and has no names for the Devialet space-sound controls
  (`audio.spaceSound` and `audio.spaceSoundMode` are not offered) or for the headrest sound's state. **The car's device classes
  refuse the helper**: the climate, bodywork, setting and instrument classes ask the process that makes one for a runtime
  permission (`BYDAUTO_<DEVICE>_COMMON`) that the ADB shell cannot hold, so every window, seat, light, drive-mode and
  temperature command failed with "Neither user 2000 nor current process has android.permission.BYDAUTO_BODYWORK_COMMON."
  ([hal-and-permissions.md](hal-and-permissions.md#dilink-50-the-device-classes-ask-for-a-runtime-permission)). The tyre pressure,
  whose class has no such permission, was read. The second log shows the permission is a *normal* one that the app already
  holds, and that `…_GET` and `…_SET` are signature-level and held by nobody. The car has no `byd_airconditioning` service; its
  `others=` list shows one called `airconditioning`. The car's own service lets the shell through by its uid, so the helper now goes
  straight to it where a class refuses ([the native route](hal-and-permissions.md#the-native-route)), the A/C included
  ([ac-service.md](ac-service.md#a-car-without-the-service)). That is built and has run in a simulator, **not on a car**: until the
  owner's next log, what works on that car is the assistant, apps, navigation, media and the tyre pressure.
- **DiLink 3.0:** known from one owner's log (beta.5, Android 10, firmware `QKQ1.210910.001` built 2026-08-06, `vehicle.type`
  `Di3.0_3.5UI`), not observed by us. The helper starts and reads the CAN-FD numbers (its label then said `DiLink 4`, see
  above). The table holds 6,732 numbers (the Leopard's 13,823), all five device classes exist, and only the `autoservice`
  answers: there is no `byd_airconditioning` service, and `airconditioning` answers as `android.os.IAirConditioningService`.
  `names=57/76`: the car has no names for the four window target positions (so `window.<door>.position` is withheld; open and
  close are not), the headrest and space-sound controls, the driver's seat belt (the greeting needs it and never starts), the
  cabin temperature, **`SETTING_PRESELECTED_DRIVING_MODE_SET` (drive modes) and `ADAS_UE_BROADCAST_POSITION_SET` (exterior
  speaker)**, and it numbers the right-rear window's percent state differently. As on DiLink 5.0 the device classes ask for
  `BYDAUTO_<DEVICE>_COMMON` (normal level, held by the app, not by the shell), and builds before beta.4 failed every window with
  that refusal and the A/C with "System service 'byd_airconditioning' not found". **With beta.5, through the native route, the
  car took the window open and close and the cabin light on and off** (`HAL returned 1`, with no read-back on this car, so
  only the owner can say the glass moved) **and the A/C power, the temperature (18 and 17) and the fan (2), which the car read
  back**; `native=reads:5/10,agree:1/1`. The owner says the car has an exterior speaker and drive modes (perhaps only Eco,
  Normal and Sport). The app offers neither because the table has no names for what it looks for; the car may hold them under
  others, which the `similar=` and `scan.…=` fields of its next log will say ([roadmap](../roadmap.md#dilink-3-and-dilink-4)).
  The in-app update also failed there, three times, with `WRONG_SIGNATURE`: on Android 10 `getPackageArchiveInfo` returns no
  certificate for a file asked about with `GET_SIGNING_CERTIFICATES` alone (seen on a stock Android 10 emulator too), which the
  check took for another signature. It now asks with both flags.
- **Other cars:** not observed by us. The lookup by name was run in a simulator built from our car's own framework, with the
  class under the classic CAN and under the Toyota numbering: each time the commands were sent to the car's numbers. That a
  table without a name withholds the command is covered by unit tests. It has been run on one DiLink 3.0 car (above), and not on a DiLink 4 car.
