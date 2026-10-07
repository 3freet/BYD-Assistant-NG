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
features. Those typed getters are not used by this project yet.

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

## Events

State changes also reach listeners (`registerListener`). The same stream is visible without any code: the
device classes log every event, for example

```text
BYDAutoSettingDevice: postEvent device_type: 1023, event_type =48c00018, value = 2
```

where `event_type` is the **state id in hex**. Looking up that id in `BYDAutoFeatureIds` names it. Capturing
`logcat` while operating a native screen is therefore a way to see exactly which ids it touches — including
ids of devices the polling probe does not cover.
