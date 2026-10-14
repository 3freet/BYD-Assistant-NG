# Front, rear or all-wheel drive

How the head unit knows whether the car is front-wheel, rear-wheel or all-wheel drive, and which axle is working
at a given moment. All of it is **read-only**: the app only reads these signals, it does not write any of them.

> As everywhere in these notes: observed on one vehicle and one firmware (a Fangchengbao Leopard 5, DiLink 5.1).
> The meaning of the numbers comes from constants the car's own framework defines and from what the car's Drive
> Mode app does with them; the live values were recorded on that one car while parked and while moving off.

## Three layers

| Layer | Feature (device) | On this car |
| --- | --- | --- |
| What the car is | `SET_DRIVE_CONFIG_TYPE` (setting 1023, id `0x34100038`), read by the framework's `getDriveConfig()` | **2** = four-wheel |
| What the car is | `SET_HAS_FOUR_WHEEL_DRIVE` (setting 1023, `0x34100027`) | 1 |
| Which axle works now | `ENERGY_STATE` (energy 1006, `0x34100018`) | 0 at rest; 1, 2, 4, 5 while driving, see below |
| What the motors do | `ENGINE_FRONT_MOTOR_SPEED` (`0x44100008`), `ENGINE_REAR_MOTOR_SPEED` (`0x25100008`) (engine 1012) | rpm, signed |

**Drive configuration** (`BYDAutoSettingDevice.DRIVE_CONFIG_*`): 1 front, 2 four-wheel, 3 rear, 4 EV four-wheel. The
car's own Drive Mode app only shows its power-distribution view when this reads 2.

**Energy state** (`BYDAutoEnergyDevice.ENERGY_STATE_*`, 0 to 33). The ones that name the driven axle:

| Value | Meaning |
| --- | --- |
| 0 | none: no drive torque (neutral, park, brake pressed) |
| 1 / 2 / 3 | electric drive on four wheels / front wheels / rear wheels |
| 4 / 5 / 6 | the same three while regenerating (feedback) |
| 7, 8, 9 | hybrid: engine plus both motors / front wheels in parallel / two power sources on four wheels |
| 17 to 33 | fuel drive (17), generating electricity (18), series rear-wheel drive (19), idling (23), series four-wheel drive (33) and the drive-assist modes. 18, 19, 23 and 33 were seen on this car |

The torque features (`ENGINE_FRONT_MOTOR_TORQUE`, `ENGINE_REAR_MOTOR_TORQUE`, the `*_ACTUAL_WHEEL_TORQUE` ones) read
"unavailable" on this car, and `ENGINE_FRONT_AXLE_TORQUE_TARGET_ALLOCATION_PROPORTION` reads 255 (invalid), so the
split can only be seen from the energy state and the motor speeds. The per-wheel torque levels the Drive Mode app
listens to (`TYRE_LF/RF/LR/RR_WHEEL_TORQUE_LEVEL`, tyre device 1016) read 7 and **never moved** through a U-turn,
Eco, Sport and the off-road modes; `SETTING_*_MOTOR_TORQUE_LEVEL` and `SETTING_M_WHEEL_*_TORQUE_RATING` stay 0.
They are for other configurations (the per-wheel motor cars); this car does not publish a torque distribution.

## Who decides

Not Android. The vehicle control unit picks the axle from the drive mode, the state of charge, the speed and the
torque asked for, and publishes it as `ENERGY_STATE`. The head unit asks for a mode (`SETTING_PRESELECTED_DRIVING_MODE_SET`,
see [drive-modes.md](drive-modes.md)) and shows what comes back.

## How the value reaches the head unit

The car sends it; the head unit never sends an energy state anywhere (`ENERGY_STATE` has no setter). The path, from
what the car's own libraries and framework contain (strings and disassembly; no bus traffic was captured):

1. The vehicle control unit publishes the state on the vehicle network. The car reports its protocol as
   `sys.car.protocol = CANFD`.
2. The cockpit microcontroller (MCU) receives the frame and passes it to the head unit's SoC over SPI
   (`/dev/spidev_ivi`; wake-up nodes `/sys/soc_mcu/soc_wakeup_mcu`). Some signals travel over Ethernet instead
   (the HAL's filter logs an `is_eth` flag, and a SOME/IP daemon runs).
3. The auto HAL module (`/system/lib64/hw/auto.default.so`, loaded with `hw_get_module` by the native `autoservice`
   daemon) decodes the frame with a per-protocol feature list (`FeatureListCanFD`), keeps the latest value, and also
   asks the MCU for values itself (`firstQuery`, a periodic query thread). A change calls the service's
   `onChangedEvent(deviceType, featureId, value, time)`.
4. `autoservice` (`android.gui.BYDAutoServer`, `libbydautoservice.so`) checks the caller's permission and posts the
   event over binder to every client that registered for that feature id (`handleChangedEvent`).
5. In an app, `BYDAutoManager` (`libbydauto.so`, `nativeGetInt`) is the binder client, and `BYDAutoDeviceManager` and
   `AbsBYDAutoDevice` sit on top: `get(deviceType, featureId)` reads the latest value, `registerListener(ids)` makes
   the service push changes, which arrive as `onDataEventReceived(featureId, value)` (that is how the Drive Mode
   app gets it).

A write takes the same route backwards (service, HAL `setInt` which encodes the frame, SPI, MCU, vehicle network).
The head unit only ever asks for a mode; the energy state is the answer the vehicle control unit gives.

## What a drive showed

Recorded with the car READY, in D, while changing mode (Normal 1, Eco 2, Sport 3 as drive-mode numbers; the energy
operation mode `ENERGY_OPERATION_MODE` reads 3 Normal, 1 Eco, 2 Sport):

| Mode | `ENERGY_STATE` | Motors |
| --- | --- | --- |
| Normal | 1 (all four) | front and rear speeds equal (for example -1369 / 1398 rpm) |
| Eco | 2 (front) while driving, 5 (front, regenerating) when lifting | front climbs to -2300 rpm while the rear **falls** 1464 → 373 rpm |
| Sport | 1 (all four) | rear picks up again within 0.4 s (373 → 1263 rpm) and tracks the front |

So in Eco the rear motor is not just idle, it is mechanically **disconnected from the wheels**: it spins down on its
own while the car speeds up, and reconnects the moment Sport is chosen. The state value is 1 in Normal and Sport and
2 in Eco; the sign of the two speeds is opposite because the motors are mirrored (forward is negative at the front).

## The off-road modes (second drive)

Drive-mode numbers as the car's screen sends them: 4 Snow, 5 Sand, 6 Mud, 7 Mountain, 8 Rock, 23 traction. Recorded
in D, R and N with the car parked or moving slowly, while switching between them and using 4L and U-turn:

| Scenario | Engine | `ENERGY_STATE` seen |
| --- | --- | --- |
| Snow (first time) | off | 1 |
| Sand, Mud, Mountain | starts at once (about 1400 to 2100 rpm, even at standstill) | 19 and 33 while moving, 18 at standstill, 23 in N |
| Traction with 4L, U-turn | running | 33 and 19 alternating with load, 18 at rest, 23 in N |
| Rock | stops | 1 (a 0.2 s flash of 2 right at the switch) |
| Snow (after the engine had stopped) | off | 0 and 1 |

So Sand, Mud, Mountain and traction run the engine as a generator (the series states), Rock and Snow stay electric
four-wheel. 19 and 33 alternate within seconds as the load changes (33 under more throttle). Their names say
rear-wheel and four-wheel, but the front motor spins with the wheels in both, so the speeds alone do not tell
which axle carries the torque.

**State 3 (electric, rear wheels only) never appeared** in any mode, gear, 4L or U-turn. Nothing on this car's
four-wheel configuration seems to use it; it is probably there for rear-drive electric models.

Other things seen:

- `ENERGY_MODE_INSTRUMENT_50` is the cluster's mode indicator: 6 in Eco, 1 in Normal and Sport.
- A mode change moves the mode number and `ENERGY_OPERATION_MODE` together, within a few milliseconds.
- `GEARBOX_AUTO_MODE_TYPE` reads 1 P, 2 R, 3 N, 4 D.
- `ENGINE_REAR_MOTOR_SPEED` flickers between -1 and 2 at rest; treat that as noise.

## U-turn

Two U-turns were recorded (one with the engine running in Sand, one electric in Snow). The state stayed 33/19 and 1
as it would otherwise, with no U-turn value of its own. What the car publishes for it: `SETTING_LEOPARD_TURN_STATUS_SIGNAL`
(3 idle, 0 turning, 1 finishing), `SETTING_LEOPARD_TURN_STEERING_WHEEL_OPERATION_PROMPT_FLAG` (1 to 3),
`SETTING_LEOPARD_TURN_TARGET_ROTATION_ANGLE_FEEDBACK` (35 to 64) and `SETTING_LEOPARD_TURN_YAW_VELOCITY_STATUS` (a ramp
0 to about 17 and back, 127 invalid). The rear motor's speed fell well below the front's during the turn (about
0.6 of it with the engine running, far lower and reversed in the electric one), which fits one rear wheel being held
or slowed to pivot; with a single front and a single rear motor speed nothing says how torque was shared.

## What the car's Drive Mode app can command

The commands that app (and its service) reference, which is the whole control surface it offers:

| Command | What it is for | Effect on the driven axle |
| --- | --- | --- |
| `SETTING_PRESELECTED_DRIVING_MODE_SET` | the mode number (Snow, Sand, Mud, Mountain, Rock, Auto, wade, custom, traction) | the only one seen to change it: Eco gives front-wheel drive, Normal, Sport, Snow and Rock four-wheel, Sand, Mud, Mountain and traction run the engine in series |
| `SETTING_FOUR_L_SWITCH_SET`, `SETTING_CRAWL_SWITCH_STATUS_SET` | 4L (low range) and crawl | none seen directly; the 4L state moved with the front-wheel switch |
| `SETTING_FRONT_DIFFERENTIAL_LOCK_REQUEST_SET`, `SETTING_REAR_DIFFERENTIAL_LOCK_REQUEST_SET` | lock a differential | none: it joins the two wheels of one axle |
| `SETTING_LEOPARD_TURN_*_SET` | U-turn (direction, angle, start allowed, diameter, quick switch) | none seen |
| `SETTING_VIOLENT_MODE_PAD_PRESELECTED_SCENE_SET` | burst scene (climbing or tugging) | none seen |
| `ENGINE_CUSTOM_AUTUAL_SUB_MODE_SET`, `ENGINE_POWER_RESPONSE_ACTUAL_STATUS_SET`, `ENGINE_CROSS_COUNTRY_RADIATING_SWITCH_STATUS_SET`, `ENGINE_CUSTOM_MODE_RESET_COMMAND_SET` | Custom mode: power response, cooling | none |
| `SETTING_SAND_ABS_ENABLE_SIGNAL_SET`, `SETTING_SNOW_CHAIN_FUNCTION_SETTING_ITEM_SET`, `RESCUE_AIRBAG_ROLL_SUPPRESSION_STATUS_SET` | sand ABS, snow chains, airbag roll suppression | none |

None of them chooses an axle. So on this car the axle follows the mode: select Eco for front-wheel drive, anything else
for all four. There is no rear-wheel-drive request.

## Torque and power controls

| Control | Values | Reads now | Status |
| --- | --- | --- | --- |
| Regeneration strength: `getEnergyFeedback()` / `setEnergyFeedback(int)` (`SET_DR_ENERGY_FB`) | 0 self-adaptive, 1 standard, 2 large, 3 and 4 the two with the other disabled, 6 custom | 2 (large) | typed framework API, the only documented torque-related control; per vehicle, not per axle |
| Power response (`ENGINE_POWER_RESPONSE_ACTUAL_STATUS`, `…_SET`; Custom mode) | not documented | 3 | follows the mode and Custom mode |
| Drive force adjust (`ENGINE_DRIVE_FORCE_ADJUST`, `…_SWITCH`, `…_SET`) | not documented | 15 (invalid), config 1, switch 1 | no screen of the car uses it |
| Front/rear axle allocation (`…TORQUE_ALLOCATION_SWITCH`, `…TARGET_ALLOCATION_PROPORTION`, `SET_WHEELS_POWER_ASSIGN_PROPORTION`) | not documented | 1, 255, 65535 | no screen uses it; unchanged in every recording; writing 0 to the front proportion setter while driving did nothing (see below) |
| Stability and traction (`SETTING_ITAC_INTELLIGENT_TORQUE_CONTROL_FLAG`, `SETTING_SUPER_ITAC_*`, `ENGINE_TRACTION_CONTROL_ACTUAL_STATUS`, `ENGINE_TC_SWITCH_STATUS`) | not documented | 1, status 1, 7, 3 | the car's own control loops; read-only for us |

What can be measured: the front and rear motor speeds and the energy state. Torque per axle or per wheel is not
published (all torque and wheel-level signals read "unavailable" or stay constant).

## Rear-wheel drive

No drive mode was found that gives rear-wheel drive on this car, and `ENERGY_STATE` 3 was never seen.

- The modes the car offers come from the `SETTING_*_MODE_CONFIGURATION_SIGN` states (0 = present): Eco, Normal,
  Sport, Snow, Sand, Mud, Mountain, Rock, Wade, Auto (intelligent), Burst (max torque), Sport+ (racing / launch) and
  Custom read 0. Rally and "turn around" read 1. None of the tiles on the Drive Mode screen is a drive-axle choice.
- Custom (id 19) only combines five things: power response, energy feedback, suspension, cooling and turn assist.
  Axle allocation is not one of them.
- Drift has 48 signals in the framework (34 `SETTING_*DRIFT*`, 13 `ENGINE_RACING_MODE_*DRIFT*`, 1 reminder), and a drift mode is
  the obvious place for rear-biased drive, but the Drive Mode app never references them and the car has no tile for
  it. The flags read inconsistently (some 0, some 1, some 3) and nothing was seen moving. One test write was made:
  `SETTING_DRIFT_LEVEL_SET` with the value `SETTING_DRIFT_LEVEL` already read (0), parked in P at 0 km/h. The car
  accepted it (return 0) and nothing changed in the level, status, function-enable, switch or racing-drift switch
  status over the next 3 seconds, so "accepted" here shows neither that the value took effect nor what the level's
  scale is. A second write of 1 (the lowest non-zero level one would expect) was accepted the same way, and
  `SETTING_DRIFT_LEVEL`, the status, the function-enable (3), the switch, the angle index and the IPB drift flags all
  stayed as they were for 3.2 s; 0 was written back afterwards. The car ignores the level outside a drift session
  (or the function is not enabled), so this signal cannot be probed from outside the mode. Finally
  `SETTING_DRIFT_MODE_ENTER_SET` = 1 was sent once (parked in P, 0 km/h), watched for 4 s, and
  `SETTING_DRIFT_MODE_EXIT_SET` = 1 sent after it. Both returned 0 (accepted) and none of the drift switch, status,
  function-enable (3), level, IPB flags, prompt flags or the racing-drift switch status moved, so the mode did not
  engage; on this car those commands, or at least that value, do nothing from a stationary start.
- The nearest thing to rear drive that was seen is state 19, "series rear-wheel drive", with the engine generating
  (Sand, Mud, Mountain, traction). The front motor still spun with the wheels there, so it is not proof of rear-only
  drive.
- The front/rear torque allocation setters in the engine group (see above) were the only control that could have
  changed the split, and no app of the car was seen using them. One was tried; see below.

### Front axle allocation write

`ENGINE_FRONT_AXLE_TORQUE_TARGET_ALLOCATION_PROPORTION_SET` (device 1012, id 0x4ef06030) was written once with 0
("no front torque", on the guess that the field is a front share). Sport mode, brake held, written 12 s after the probe
started and restored to 255 after 60 s. Before the write the car was parked in P; the driver then shifted P → D → N → R
→ D and crept at 0 to 9 km/h while the probe polled every second.

- The setter returned 0 (accepted).
- `PROPORTION` read 255 before, during and after. `SWITCH` stayed 1.
- `ENERGY_STATE` followed the gear only: 0 in P, 1 (EV 4WD) in D and R, 0 or 1 in N. It was never 2 or 3.
- Front and rear motor speeds were equal in size and opposite in sign (about -334/+331 at 3 km/h, -838/+862 at 9 km/h),
  so both motors were turning and driving the wheels. The sign flips in R.

So the write was accepted and ignored: it neither changed the readable proportion nor cut the front motor. Either the
value is not a front share, the setter only works in a state or with a source this probe is not (for example a
privileged caller or a driving app that holds the mode), or the readable field is not fed by it at all. A second value
(for example 100) was not tried; no other axle control is known.

Not tried: Auto (21), Wade (9), Sport+ (20, for a track only) and Custom while driving.

## Not understood yet

- `SET_DECOUPLING_ELECTROMAGNETIC_LOCK_STATE` (reads 3) and `SET_DECOUPLING_POSITION_SENSOR_STATE` (reads 7) did not
  change across the Eco and Sport switch, so they are not a live indicator of the disconnect seen above.
- `SETTING_FOUR_L_SWITCH` (normally 2) went 2 → 4 → 1 right when the state changed from 1 to 2 (Eco, front-wheel
  drive) and 1 → 4 → 2 when the car was shifted to N, as if the same actuator that engages 4L also disconnects the
  rear drive unit. Seen twice, not tested further.
- `ENGINE_FRONT_REAR_AXLE_TORQUE_ALLOCATION_CONFIG` / `_SWITCH` (both read 1) and
  `ENGINE_FRONT_AXLE_TORQUE_TARGET_ALLOCATION_PROPORTION` (255, invalid) have setter variants, which suggests a
  front/rear torque split that can be switched on and targeted. None of them changed in any recording (Eco, Normal,
  Sport, the off-road modes, U-turn), so it is not how Eco gets its front-wheel drive. The names sit in a feature-id
  table that 18 of the car's apps carry (video player, SD-card service and so on), so their presence says nothing
  about use; the Drive Mode app's Custom mode has no axle option, and no screen of the car was seen using it. It is
  in the motor/engine class the app never writes. Its sibling `SET_WHEELS_POWER_ASSIGN_PROPORTION` (65535) and
  `ENGINE_DRIVE_FORCE_ADJUST` (15, config 1, switch 1) look like the same family. What the values mean is unknown.
  Writing 0 to the proportion setter while creeping in D did nothing (see "Front axle allocation write").
- Hybrid values 7, 8 and 9 (engine driving the wheels directly) were not seen; the engine only ran as a generator.
