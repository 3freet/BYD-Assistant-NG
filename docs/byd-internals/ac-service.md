# The air-conditioning service

The A/C is **not** driven through the generic `get`/`set` calls. It is a separate system service with its own,
much smaller set of property ids.

## Reaching it

| | |
| --- | --- |
| System service name | `byd_airconditioning` (wrapped by `com.byd.ac.BydAcManager`) |
| Jar on the unit | `/system/framework/com.byd.ac.jar` (decompile it with jadx to read the ids) |
| Master interface | `com.byd.ac.IBydAcService` |
| Sub-service for the main A/C controls | key `AC_AIRCONDITIONER_SERVICE`, interface `com.byd.ac.IAcAirConditioner` |
| Sub-service for seat heat/ventilation | key `AC_SEAT_VENTILATION_HEATING_SERVICE`, interface `IAcSeatVentilationHeating` |

`ServiceManager.getService("byd_airconditioning")` is hidden API but not root-gated; reflection reaches it.

The seat sub-service exposes `setVentHeatLevel(seatId 1–4, type, level)` (transaction 7) and the getters
`getHeatLevel` (5) and `getVentLevel` (4). What `type` selects is not known, so this project drives the seats
through the generic Setting-device controls instead (see [vehicle-commands.md](vehicle-commands.md)).

## Wire format

All three calls are plain Binder transactions. `writeInterfaceToken(descriptor)` always comes first.

| Call | Transaction | Request | Reply |
| --- | --- | --- | --- |
| get a sub-service | 3 on the master | `writeString(key)` | `readException()`, then `readStrongBinder()` |
| set a property | 3 on the sub-service | `writeInt(1)` (count), `writeInt(id)`, `writeInt(area)`, `writeString("java.lang.Integer")`, `writeValue(value)` | `readException()` |
| get a property | 2 on the sub-service | `writeInt(id)`, `writeInt(area)` | `readException()`; `readInt()` is `0` for null, otherwise the value follows: `readInt()` id, `readInt()` area, `readString()` class name, `readValue()` |

## Areas

A property is addressed by `(id, area)`. The service only answers for areas it knows:

| Area | Meaning |
| --- | --- |
| `256` | main / driver zone |
| `272` | passenger zone |
| `0`, `-1` | **accepted and ignored**: every call succeeds and nothing happens |

The last row is the trap: a set with the wrong area returns normally. **Always read the value back.** The
value can take a moment to appear; polling for about 1.5 s (12 × 120 ms) is enough in practice.

## Property ids

From `com.byd.ac.PropertyIds.AirConditioner` in the unit's own jar:

| Id | Name | Id | Name |
| --- | --- | --- | --- |
| 101 | power | 111 | middle-left power |
| 102 | temperature | 112 | rear power |
| 103 | fan (wind) level | 113 | air direction (wind mode) |
| 104 | compressor (the A/C button) | 114 | control mode |
| 105 | ventilation | 115 | temperature unit |
| 106 | compressor max | 116 | wind-free mode |
| 107 | front defrost | 117 | sweep mode |
| 108 | rear defrost | 118 / 119 | avoid / blow to people |
| 109 | internal cycle (recirculation) | 120 | slide-close mode |
| 110 | temperature sync | 121 | work mode |

Ids 122–129 are the air-vent angle controls. Ids 301–319 are the air-quality values (PM2.5 inside/outside, the
anion detector, quick air-clean) and change by themselves.

## Observed values

Recorded by toggling every control on the native A/C screen:

| Property | Values | Notes |
| --- | --- | --- |
| 101 power | `1` on, `0` off | switching off resets the fan level to 0 |
| 102 temperature | integer °C, 17–33 | |
| 103 fan level | 0–7 | |
| 104 compressor | `1` on, `0` off | |
| 105 ventilation | `1` on | fan only, with outside air: also forces recirculation to 0 and the compressor off |
| 107 front defrost | `1` / `0` | on also sets fan 7, air direction 5 and outside air |
| 108 rear defrost | `1` / `0` | |
| 109 internal cycle | `0` outside air, `1` recirculation | defrost and ventilation both force `0` |
| 113 air direction | `1` face, `2` face + feet, `3` feet, `4` feet + defrost, `5` defrost | the car also reports `7` for a mode not covered here |
| 114 control mode | `0` auto, `1` manual | any manual change flips it to `1`; there are matching "manual sign" flags |

Side effects to know when scripting it:

- Turning **ventilation off** hands the A/C back to **auto** (compressor on, recirculation on, fan raised).
- Turning **front defrost off** also falls back to auto.
- To put it back to *manual* afterwards, set the fan level — a manual fan change flips the control mode.

## Learning more of it

The service logs every property change it sees, in the system log:

```text
[BydAc]BaseRepository: onDataChanged propertyValue = PropertyValue{mId=109, mArea=256, mValue=0}
```

Capture `logcat` while you press things on the native A/C screen and you have the id and value of each one.
This is the quickest way to learn a new control, faster than the polling probe.
