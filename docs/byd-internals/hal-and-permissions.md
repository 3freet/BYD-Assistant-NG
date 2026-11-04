# The vehicle HAL, ids and permissions

## The classes

The head unit's framework contains a family of device classes in `android.hardware.bydauto.<name>`, each named
`BYDAuto<Name>Device` and extending `android.hardware.bydauto.AbsBYDAutoDevice`. On the unit this project was
built on, the framework held these (names are lower-case package names):

`ac adas audio auxiliary bigdata bodywork charging collision cputemprature doorlock doormirror dtc energy
engine funcnotice gb gearbox instrument light location motor mqtt multimedia ota panorama phone pm2p5 power
qcfs radar radio reminder rescue rse safetybelt security sensor setting signal special speed statistic test
time tyre vehicledata version wiper yun`

Each has a static `getInstance(Context)`. Many also expose typed getters (the system logs show calls such as
`BYDAutoStatisticDevice.getElecPercentageValue`, `getEVMileageValue`, `getElecDrivingRangeValue`,
`getFuelDrivingRangeValue`, `getTotalMileageValue` and `BYDAutoSpeedDevice.getCurrentSpeed`) — so battery,
fuel, range, odometer and speed are readable, which is the basis for "how much range do I have?" style
features. Of these, [tyre pressure](tyres.md) and the outside temperature (`BYDAutoInstrumentDevice.getOutCarTemperature()`,
degrees in the cluster's temperature unit — `getUnit(1)` is `1` for Celsius — with `195` meaning "no reading") are
used by the app; the others are not used by this project yet.

## Device types

The generic calls take a *device type* number. Confirmed by reading the class constants and by successful
reads and writes:

| Device | Type |
| --- | --- |
| Ac | 1000 |
| Bodywork | 1001 |
| Audio | 1002 |
| Light | 1004 |
| Security | 1010 |
| Setting | 1023 |

Also seen as the `device_type` of events in the system log: Power 1005, Instrument 1007, Pm2p5 1008,
Statistic 1014, Safety (seat belt) 1042, Special 1049.

## The two generic calls

Both are declared on `AbsBYDAutoDevice`:

```java
protected int get(int deviceType, int featureId)                       // read one state
protected int set(int deviceType, int[] featureIds, int[] values)      // write (usually one id, one value)
```

They are `protected`, so they must be found with reflection **up the class hierarchy** (`getDeclaredMethod` on
the concrete device class alone does not find them) and made accessible with `setAccessible(true)`. The
helper in this project does exactly that (`ReflectionVehicleController.findMethod`).

Return values:

- `set` returns `0` when the request was **accepted**. That is not the same as the car having done it.
- Negative values are errors. `-10011` is "not supported / no value".
- `get` returns the raw state. Two values mean "nothing here": `-10011` (unsupported) and `65535` (not
  fitted or not available). Options the car does not have usually read as one of these.

## Feature ids

Ids are 32-bit integers (some print as negative). The real values live in the framework class
`android.hardware.bydauto.BYDAutoFeatureIds`, which has nested classes per device group (`Ac`, `Audio`,
`Bodywork`, `Light`, `Setting`, `Security`, …) plus a "top" group. **Use the one in the head unit's own
framework**: copies bundled inside other apps can be stubs with every value zero.

Naming convention that held for everything we examined:

| Pattern | Meaning |
| --- | --- |
| `SOMETHING_SET` | the **control**: write here |
| `SOMETHING` | the **state**: read here to see what the car did |
| `…_hal_only`, `…_RCS_…`, `…_RSE_…` | internal or remote-control variants; not for direct use |
| `…_CONFIG`, `…_FLAG`, `…_ONLINE` | whether an option is fitted or reporting |

The numbers are **not the same on every car**: the class that holds them picks them when it loads, from the car's bus
protocol (`sys.car.protocol`: CAN-FD, Toyota or classic CAN), so the same number can mean a different window on one car
than on another. Only DiLink 5.1 (CAN-FD) has been observed. See [platforms.md](platforms.md) for where the numbers come
from and how the app takes each car's own number by name.

The control and its state are different numbers, and the **value written is not always the value read**
(see [findings](findings-and-dead-ends.md): the fridge set-point and the massage "off" are the two clearest
examples). Always read the state back.

## Permissions — why a helper process

Every call is checked against permissions of the form `android.permission.BYDAUTO_<DEVICE>_GET`,
`…_SET` and `…_COMMON`:

- `…_COMMON` can be declared in an app's manifest and is granted.
- `…_GET` and `…_SET` are **signature-level**. Declaring them does nothing, and `pm grant` answers that they
  are "not a changeable permission type".
- From an ordinary app process the call is refused, and the log shows `[getInt] permission deny!`. The system
  side also logs `BYDAutoService::checkGetPermission: permission whiteList`, so the check is a whitelist.

The **ADB shell identity (uid 2000) is accepted.** The same read returned real values from the shell and a
`SecurityException` from the app's own uid. So the app starts a helper process as the shell user:

```text
CLASSPATH=<path to the app's APK> app_process /system/bin <helper class> --serve
```

`app_process` runs a `main()` from the APK's classes with no `Application` or `Activity`; it borrows a system
`Context` for the device classes with
`ActivityThread.systemMain().getSystemContext()` (after `Looper.prepareMainLooper()`). The app reaches the
shell user through ADB over the loopback interface (the `dadb` library), which needs **ADB debugging switched
on** in the head unit. See [SECURITY.md](../../SECURITY.md) for what that implies.

In this project the helper reads one command per line (`<commandId> <value>`), re-validates it against the
command registry (so the shell process never trusts the app), and answers with one `BYDRESULT …` line. It stays
alive between commands: a cold start costs about 1.3 s, a command in a running helper about 0.2 s.

### DiLink 5.0: the device classes ask for a runtime permission

A second check showed up in the log of a BYD Song Plus (DiLink 5.0, Android 12). Six device classes (`ac`, `bodywork`,
`doorlock`, `instrument`, `panorama` and `setting`) carry a `…_COMMON` permission next to their `…_GET` and `…_SET` (the
constants are in the DiLink 5.1 framework too), and BYD's API documentation for them says that "when `getInstance(Context)` is
used" the app must be granted that runtime permission, while `…_GET` and `…_SET` need only be declared. On that car it is
enforced when the device object is made, against the process that asks, and the vehicle helper, which is the ADB shell
(uid 2000), does not hold it:

```text
dispatch window.driver(value=open -> 1) -> Failure(INVOCATION_FAILED, Neither user 2000 nor current process has android.permission.BYDAUTO_BODYWORK_COMMON.)
```

That is `Context.enforceCallingOrSelfPermission` (the "Neither user … nor current process" wording is its own), reached through
reflection, which is why the exception arrives inside an `InvocationTargetException` that has no message of its own. What was
refused on that car, and what was not:

| Device | Refused for want of | What failed |
| --- | --- | --- |
| Bodywork | `BYDAUTO_BODYWORK_COMMON` | the windows and the sunroof |
| Setting | `BYDAUTO_SETTING_COMMON` | seat ventilation, the cabin light, the drive mode |
| Instrument | `BYDAUTO_INSTRUMENT_COMMON` | the outside temperature |
| Ac | (not named: the wrapper hides it) | the inside temperature |
| Seat and door | (not named) | the greeting's read ("Could not read the seat and the door yet") |
| Tyre | none | **the tyre pressures were read**, from the same helper |

So the car's native side accepts the shell on that car as it does on DiLink 5.1 (the tyre read went through it); what stops the
helper is the Java check in the device classes. The DiLink 5.1 framework we pulled has the same `…_COMMON_PERM` constants and
nothing in `android.hardware.bydauto` enforces them (no `enforce…` or `check…Permission` call), and the helper is accepted
there. `…_COMMON` is a runtime permission, which a package can be given (`pm grant`, or the usual dialog) if it declares it; the
shell is not a package that can, and a `Context` made for another package does not help, because the check is made against the
uid of the process, or against the caller's uid while a Binder call is being handled.

The app words the refusal for the person in the car ("This car's software does not let this app use the windows, doors and
sunroof. Nothing was sent.", `PermissionRefusal`), and the helper's log line carries `perms=`: for each of the permissions of
seven devices, how the system defines it, whether the helper holds it and whether the app does. The same car's next log
(beta 3) answered it:

| Permission | Defined as | Helper (shell) holds | App holds |
| --- | --- | --- | --- |
| `BYDAUTO_<DEVICE>_COMMON` | **normal** | no | yes where the app declares it (bodywork, setting, climate, audio) |
| `BYDAUTO_<DEVICE>_GET` and `…_SET` | **signature** | no | no |

So `…_COMMON` is not a hard gate: any app that declares it has it, and ours does. What no app can have is `…_GET` and `…_SET`,
and the helper does not have them either, yet its tyre read worked: the native service does not decide by those permissions
for the shell (next section).

### The native route

The device classes are a layer over the car's native service, `autoservice`. The car's own libraries show how it decides who
may call it (read from the disassembly of `BYDAutoService::checkGetPermission` and `checkSetPermission` in
`libbydautoservice.so`, which agree): it takes the uid of the caller, and **below 10,000 (root, system, the shell) it lets the
call through with no permission check**; for an app's uid it asks for the device's signature-level `…_GET` or `…_SET`
permission, which no app can hold. That is why the shell is accepted on every DiLink and an app is refused, and why the one
thing in the helper's way on a DiLink 5.0 car is the Java check of the device classes, which is made before any call reaches
the service.

Where a device class refuses the helper for want of its `…_COMMON` permission, the helper therefore makes the same read or
write one layer lower, as a call to the service itself (`NativeAutoservice`), for the same registry commands and with the
same read-back. The call layout is the one in the car's client library (the binder stub in `libbydauto.so`, `onTransact`,
checked against its jump table and handlers):

| Call | Parcel written | Reply |
| --- | --- | --- |
| `5` read an int | the service's interface token, device type, feature id | status (0 when it answered), value |
| `6` write an int | the interface token, device type, feature id, value (and no callback binder) | status (1 or more when it took, 0 when accepted and ignored, negative when refused) |
| `7` read a float | as `5` | status, the float's bits |

The interface token is the descriptor the service's binder gives (`getInterfaceDescriptor`), so it is asked for, not written down.
The A/C commands of a car without the climate service name the one feature their typed setter writes
(`withoutAcService.nativeFeatureName`, for example `Ac.AC_WIND_LEVEL_SET`), which is what is written; the temperature is written
only on a car that shows Celsius, as on the device route.

**The drive mode** is not a registry command (`DriveModeCommand` has its own code) and takes the same route for the same reason.
For one int feature the Setting device's `get(int[], Class)` and `set(int[], BYDAutoEventValue)` end, in the car's framework, in the
device manager's `getInt` and `setInt`, so those are the two calls made: `getInt` of `SETTING_TARGET_DRIVING_MODE`, the mode the car
reports, and `setInt` of `SETTING_PRESELECTED_DRIVING_MODE_SET`, the one feature the car's own drive-mode screen writes. What is
checked before a request is sent does not depend on the route: the value is one of the listed modes, the car names the feature with
the verified id, and the car is below 80 km/h (an unknown speed is not). The speed is read through the Speed device class, which has
no `…_COMMON` permission, and the four drive mode requests in the Song Plus logs all passed that check before they were refused at
the Setting device, so the speed can be read on a DiLink 5.0 car and the limit applies there as anywhere. Where a car does not give
its speed no request is sent, and the answer says the speed was not reported; there is no native speed read.

When it is used: by default only after a device class has refused the helper (and from then on for that class); a test switch,
`setprop debug.bydang.nativeroute always|never|auto`, prefers or forbids it, and `setprop debug.bydang.acroute service|device|none`
sets the climate route by hand. A class the car has refused is remembered for the life of the helper, whichever part of the helper
saw it first (the start-up check below, a command, the drive mode), so a refusal costs one failed attempt for each class and not
one for each command. The log shows `[native]` after each note that came this way (the model is not told), and the
helper's line carries `native=reads:<read>/<tried>,agree:<same>/<compared>,ms:<n>`: how many of a dozen states the commands read back
the service answered, how many of those it answers the same as the device class where the car lets the helper use that
too (a window that moved between the two reads is a difference too; every state differing would mean the answers are read
wrongly), and how long the check took in milliseconds. The climate service of a DiLink 5.0 car was listed as `airconditioning`,
not `byd_airconditioning`; that name is used for the A/C service route when its binder says it speaks `com.byd.ac.IBydAcService`,
and the log shows `acalias=` with what it says in any case.

**Status: not run on a car.** It has run in a simulator built from the DiLink 5.1 car's own device classes, with a fake service that
answers in the layout above and refuses an app uid, and with the device classes refusing the helper as the Song Plus's do (the
registry commands and the drive mode both went through the service and were confirmed); the layout is read from the vendor's
binaries, not observed on a car. To check it on a car whose device classes work (the Leopard), with the debug build installed
(`./gradlew :app:installDebug`) and `adb` connected:

```text
tools/check-native-route.sh <adb serial>            # read-only
tools/check-native-route.sh <adb serial> --write    # also one reversible write: the driver's seat heating, low then off
tools/check-native-route.sh <adb serial> --drive    # also the drive mode: Eco (Normal if the car is in Eco), then back
```

The first starts the helper and reads the `native=reads:N/M,agree:A/B` field of its line: N close to M, and A equal to B (or one
or two short for a window that moved), proves the answers are read right without writing anything. The second sets
`debug.bydang.nativeroute always`, sends the seat command through the debug tool receiver and requires the app log to show it
confirmed with `[native]`; it puts the switch back to `auto` when it ends. Run it parked, with someone in the car to see the seat's switch.
The third asks for the drive mode the way the assistant does (the debug receiver runs the same request, with the same checks, so it
does nothing at 80 km/h or more), requires the app log to show the car changed to it with `[native]`, and puts the car back in the
mode it was in (a mode the assistant cannot ask for is left, and the script says so). The options can be given together.

## Events

State changes also reach listeners (`registerListener`). The same stream is visible without any code: the
device classes log every event, for example

```text
BYDAutoSettingDevice: postEvent device_type: 1023, event_type =48c00018, value = 2
```

where `event_type` is the **state id in hex**. Looking up that id in `BYDAutoFeatureIds` names it. Capturing
`logcat` while operating a native screen is therefore a way to see exactly which ids it touches — including
ids of devices the polling probe does not cover.
