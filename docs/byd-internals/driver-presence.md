# Is the driver in the seat?

The car reports whether the driver's seat is occupied, as an ordinary read-only state on the Safety (seat belt)
device. It was found for the planned greeting when the driver sits down (see [../roadmap.md](../roadmap.md)),
which speaks only when the car has woken, the driver has sat, and the driver's door has closed.

> As everywhere in these notes: observed on one vehicle (left-hand drive, DiLink 5.1) and one firmware. Nothing
> here was checked on another model.

## The signal

| Device | Id | Meaning |
| --- | --- | --- |
| Safety (`safetybelt.BYDAutoSafetyBeltDevice`, type 1042) | `SAFETY_BELT_PASSENGER_COMMAND_FRONT_ROW_SEAT_LEFT`, `0x31200040` | `2` the driver's seat is occupied, `1` it is empty |

The value is a state, so a plain `get` returns it, and the same value is posted as an event whenever it changes.
Read with the read-only probe (`Feat 'SAFETY_BELT_PASSENGER_COMMAND' read`):

| Who was where | `FRONT_ROW_SEAT_LEFT` |
| --- | --- |
| Driver in the seat | `2` |
| Driver standing outside the car | `1` |
| Driver out, someone in the rear right seat | `1` |

The framework's own names for the seat states (`PassengerStatus`: nobody `0`, somebody `1`) do not match what the
car sends: the values here are `1` for empty and `2` for occupied. Trust the reading, not the constants.

The same device carries the other seats under the same naming (`..._FRONT_ROW_SEAT_RIGHT`, `..._SECOND_ROW_SEAT_LEFT`,
`_MID`, `_RIGHT`). All of them read `1` with nobody on them. A person in the **rear right seat** did not change its
value, so the rear seats do not appear to report occupancy on this car. The front passenger's seat was not tried with
someone in it.

## What a sit and a stand look like

Recorded with the read-only watcher plus the system log (every event, including ones the watcher does not poll).
Times are one run on the car, parked, with the driver's door as the only door used:

| Time | What happened | Events |
| --- | --- | --- |
| 22:10:40 | belt fastened | belt state `0xA9400018` = 1 |
| 22:11:04 | belt unfastened | belt state `0xA9400018` = 0 |
| 22:11:12 | driver's door opened | `BODYWORK_LEFT_HAND_FRONT_DOOR` 0 → 1 |
| 22:11:24.9 | driver stepped out | seat `0x31200040` = 1 |
| 22:11:39.5 | door closed (driver outside) | door 1 → 0 |
| 22:11:47.5 | door opened again | door 0 → 1 |
| 22:11:52.7 | driver sat down | seat `0x31200040` = 2 |
| 22:12:00.9 | door closed (driver inside) | door 1 → 0 |
| 22:12:07 | belt fastened | belt state `0xA9400018` = 1 |

- The seat state follows the person, not the belt and not the door: it changed with the belt in either state and
  with the door open.
- **Sitting came first, the door closed about eight seconds later.** That is the order the greeting needs
  (seated, then door closed); the door signal alone does not say who is inside, as the closing at 22:11:39.5 shows.
- An instrument-cluster id (`0x28F0002C`, device 1007) flipped at the same instants as the seat signal and also
  with the belt, `2` on getting out or fastening and `1` on sitting or unfastening. It looks like the driver's belt
  warning light; it was not needed and its meaning is not confirmed.
- `SETTING_MAIN_DRIVE_SEAT_SYSTEM_WORKING_STATUS` (Setting device) moved 1 → 5 when the door opened and 1 → 4 when
  it closed, each going back to 1 about two seconds later. It follows the door, not the person; the name suggests
  the driver's seat moving to ease getting in and out, which was not checked.

## The door

`BODYWORK_LEFT_HAND_FRONT_DOOR` (Bodywork device, id `692060168`): `0` closed, `1` open. On this left-hand-drive car
it is the driver's door; on a right-hand-drive car the driver's door is presumably the other front one, not
checked.

## Waking from sleep

Recorded across one real round: car switched off, locked, left until its network slept, unlocked, driver's door
opened, sat, door closed, started. The recorders were processes started over adb (not an app); they survived the
power-off and kept logging. Times are the system log's own, to the millisecond, except where noted:

| Time | What happened | Where it shows |
| --- | --- | --- |
| 22:36:46.5 | car switched off | `SET_VEHICLE_STATE` 2 → 0, `BODYWORK_POWER_LEVEL` 2 → 0, `SET_START_KEY_STATE` 0 → 3 → 0 |
| 22:36:48.9 | every third-party app is stopped, our dev app included | events buffer: `am_kill … stop <package> due to quickboot` |
| 22:37:00 | locked | door lock states 1 → 2 |
| 22:37:01 – 09 | winding down | `BODYWORK_AUTO_SYSTEM_STATE` (id `315621421`) 0 → 1 → 2 |
| 22:38:05.8 | network sleep | log: `POWER_MCU_REPORT_NETWORK_SLEPP_WAKEUP_STATUS` goes from `0101` to `0102` |
| 22:38:08 – 10 | the car's ECUs report offline | many `BODYWORK_ONLINE_HAS_*` 1 → 0 |
| 22:38:37.7 | **wake**, by the unlock | log: `OmsService: OMS: CAR_STATE_TYPE_NETWORK_WAKEUP`, status back to `0101`; `AUTO_SYSTEM_STATE` 2 → 0 (22:38:37.96); door locks 2 → 1 |
| 22:38:44.5 | driver's door opened (seven seconds after the unlock) | `BODYWORK_LEFT_HAND_FRONT_DOOR` 0 → 1 |
| 22:38:44.8 | **head unit's screens come on** (0.3 s after the door) | log: `AccModeManagerService: startAccOn … wakeup`; `BODYWORK_POWER_LEVEL` 0 → 2 |
| 22:38:49.9 | driver sat down | seat `0x31200040` 1 → 2 |
| 22:38:50.4 | door closed (0.55 s after the seat) | door 1 → 0 |
| 22:38:58 – 59 | brake pressed, car started | `SET_VEHICLE_STATE` 0 → 2 |
| 22:39:08.0 | **our app is started again**, 23 s after the screens came on | the app's boot receiver: `BOOT_COMPLETED` |

What this shows:
- The seat and the door events are delivered normally right after a wake, and a read afterwards gives the true
  state (seat `2`, door `0`, with the driver seated).
- The wake has three early markers: the car side (`CAR_STATE_TYPE_NETWORK_WAKEUP` and `AUTO_SYSTEM_STATE` back to
  `0`, at the unlock), the head unit's screens (at the door opening), and the app's own start (`BOOT_COMPLETED`).
  `AUTO_SYSTEM_STATE`'s values are only known from this sequence (0 awake, then 1, then 2 asleep; the names are a
  guess).
- **The apps are stopped when the car is switched off, not when it wakes, and are only started again by
  `BOOT_COMPLETED`, which came 23 s after the screens in this run.** An app that waits for that broadcast starts
  after the driver has sat and closed the door (here 17 s after the door closed), so it cannot watch the three things
  happen: it has to read the seat and the door when it starts, and treat the broadcast itself as the wake. The delay
  is one sample; it may vary.
- Seat before door close held in both runs, 8 s apart in the first and 0.55 s in the second.

This was a short sleep (the lock to the wake took 98 s). A long sleep, such as overnight, may differ, in the
delay to `BOOT_COMPLETED` in particular.

## What the app does with it

The greeting (see [../roadmap.md](../roadmap.md)) reads both states when the wake broadcast arrives, then every 0.4 s,
and acts as soon as the driver is seated with the door shut (at once if that is already so at the first read). The
first real run, with a retry that waited too long for the wheel-button service to bind, took 9.6 s from `BOOT_COMPLETED`
to the first word: the service took 4.1 s to bind (a 3 s wait to see whether the system would bind it, then a 1 s gap
in the nudge), and the greeting only noticed at its next retry 3.3 s after that.

## Not yet known

- Whether the delay to `BOOT_COMPLETED` is always about 23 s, and what a night's sleep changes.
- What counts as "occupied": a bag or a child seat on the driver's seat was not tried.
- Whether the car starting up again with no sleep in between (switched off for a minute or two, with the network
  still awake) also sends `BOOT_COMPLETED`.
