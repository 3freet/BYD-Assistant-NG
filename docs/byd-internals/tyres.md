# Tyre pressure

Tyre pressure is **read-only** data: there is nothing to operate, and reading it is safe. It is the reference
example of the typed getters mentioned in [hal-and-permissions.md](hal-and-permissions.md), and the app uses it
for the `get_tyre_pressure` function.

> As everywhere in these notes: observed on one vehicle and one firmware. The unit conventions below were
> *inferred* from numbers the car's own cluster produces; check them against your car's tyre screen before
> relying on them.

## Where it comes from

Two device classes carry it. Both are `android.hardware.bydauto` classes obtained with `getInstance(Context)`, and
both are refused to an ordinary app process (see the permission model) — read them as the ADB shell user, as the
helper does.

| Device | Class | Type | Gives |
| --- | --- | --- | --- |
| Tyre | `tyre.BYDAutoTyreDevice` | 1016 | pressure per wheel, the car's own low/high verdict, leak, sensor signal, sensor battery, system state, coarse temperature state |
| Instrument | `instrument.BYDAutoInstrumentDevice` | 1007 | what the dashboard shows: pressure and temperature per wheel in the **display unit**, plus colour codes |

Permissions: `android.permission.BYDAUTO_TYRE_GET` (and `…_SET`, which nothing here uses). `hasFeature` takes the
strings in the class's `FEATURE_*` constants; `hasFeature("TyrePressureMonitor")` is `1` on a car with a direct
tyre pressure monitor, `0` without one.

## The getters

All take a wheel **area** except the system-wide ones.

| Call (`BYDAutoTyreDevice`) | Result |
| --- | --- |
| `getTyrePressureValue(area)` | pressure, **kPa** (range 0–4094) |
| `getTyrePressureState(area)` | `0` normal, `1` over-pressure, `2` under-pressure — the car's own judgement |
| `getTyreAirLeakState(area)` | `0` none, `1` quick leak, `2` slow leak |
| `getTyreSignalState(area)` | `0` sensor heard, `1` sensor signal error |
| `getTyreBatteryValue(area)` / `getTyreBatteryState()` | sensor battery voltage (0–40) / `0` normal, `1` low |
| `getTyreTemperatureState()` | `0` normal, `1` very high, `2` high, `3` sleep — one state for the whole car, no values |
| `getTyreSystemState()` | `0` normal, `1` self-checking, `2` signal abnormal, `3` breakdown, `4` masked |
| `getIndirectTyreSystemState()` | the indirect (ABS-based) system, absent on a direct-sensor car |

`getTyrePressureValue(0)` is not a wheel: it returns `-2147482645` (`TYRE_COMMAND_INVALID_VALUE`), which is how
an out-of-range area answers. `-10011` and `65535` mean "not available" as everywhere else.

### Wheel areas

The two devices number the wheels differently.

| Wheel | Tyre device area | Instrument device area |
| --- | --- | --- |
| front left | 1 | 3 |
| front right | 2 | 1 |
| rear left | 3 | 4 |
| rear right | 4 | 2 |

The tyre device's numbering is the class's own `TYRE_COMMAND_AREA_*` constants. The instrument numbering was
matched by comparing the same wheel's values on both devices.

## Units — how they were pinned down

The tyre device returns small integers (107, 112, 120, 117 on the unit this was built on). The cluster returns
155, 162, 174, 169 for the same wheels, and `getUnit(2)` (the cluster's *pressure unit*) answered `2`.

- 107, 112, 120, 117 **kPa** are 15.5, 16.2, 17.4 and 17.0 **psi** (× 0.145038).
- The cluster values are exactly those psi figures × 10, **cut off, not rounded** (16.97 psi shows as 169).

So the tyre device reports kilopascals, and the cluster reports tenths of its display unit, with unit code `2`
meaning psi. Only code `2` has been confirmed; other codes (bar, kPa) were not observed, so the app names the
car's display unit only when the code is `2`.

Temperatures are whole degrees from `getWheelTemperature(area)` and agreed with the outside temperature from
`getOutCarTemperature()` while parked; they are in the cluster's temperature unit (`getUnit(1)`, `1` observed),
so the app reports them only when that is `1`.

## Practical notes

- The tyre device's own temperature ids (`TYRE_*_TEMPERATURE_VALUE`) answered "not available" on this unit; take
  temperature from the instrument cluster.
- `getAllStatus()` asks the car to republish everything. The read-only probe skips it and the app never calls it:
  the plain getters return the last known value.
- A parked car may hold the last values from when it was last driven; the sensors report on movement.
- On the unit this was built on, all four wheels read 15–17 psi and the car itself flagged every one as
  under-pressure (state `2`) — the verdict comes from the car's thresholds, not from the app.

## How to look at it on your car

With the [probes](research-method.md) built and pushed:

```bash
# every getter of the tyre device, tried for areas 0-4
adb shell "CLASSPATH=/data/local/tmp/probes.dex app_process /system/bin Getters android.hardware.bydauto.tyre.BYDAutoTyreDevice 4"
# the same for the cluster
adb shell "CLASSPATH=/data/local/tmp/probes.dex app_process /system/bin Getters android.hardware.bydauto.instrument.BYDAutoInstrumentDevice 4"
# the feature ids behind them, with current values
adb shell "CLASSPATH=/data/local/tmp/probes.dex app_process /system/bin Feat 'TYRE|TIRE' read"
```
