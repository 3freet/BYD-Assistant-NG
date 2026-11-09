# Drive modes

The car's own drive-mode screen switches between Normal, Eco, Sport and the off-road modes. The app can do the
same by voice (`set_drive_mode`, "switch to sand mode"). This page records how the car's screen does it, so that
the app does exactly that and nothing else.

> As everywhere in these notes: observed on one vehicle and one firmware. Which modes exist differs between
> models. The app checks that the car's framework names the feature the same way before it offers or writes
> anything, and the car itself decides whether to accept a mode.

## What the car's screen does

The drive-mode app (`com.byd.dlc.drivingmode`) makes **one** write to select a mode, on the setting device:

`set(deviceType 1023, SETTING_PRESELECTED_DRIVING_MODE_SET = 0x4C123030, <mode number>)`

and the car answers by moving the state `SETTING_TARGET_DRIVING_MODE` (`0x0F4000A8`) to the same number, usually
within 0.1–0.25 s, then shows "<Mode> mode enabled". `SETTING_ACTUAL_DRIVING_MODE` (`0x2D000198`) follows it. The
app itself checks almost nothing before writing (it debounces taps and asks for acknowledgement before wade and
warp); every real condition is decided by the car, which simply does not change mode when it refuses.

A `set` that returns `0` only means *accepted*, as everywhere: the read-back of the target is the proof.

## Mode numbers

| Number | Mode | Number | Mode |
| --- | --- | --- | --- |
| 1 | Normal | 7 | Mountain |
| 2 | Eco | 8 | Rock |
| 3 | Sport | 9 | Wade (not offered) |
| 4 | Snow | 20 | Warp (not offered) |
| 5 | Sand | 21 | Auto |
| 6 | Mud | 255 | Exit the off-road mode |

The off-road screen sends 4, 5, 6, 7, 8 and 21 (plus 9 and 20 after an acknowledgement, 19 for a custom mode and
23 for traction). 255 is its exit button. Eco, Sport and Normal are **not** on that screen: the car's own switch
changes them without any Android-side write. Written to the same feature, Eco and Sport were accepted and
followed by the car too (checked: Normal → Eco → Sport, and exit from Rock returned to Normal), which is what the
app uses for "switch to eco"; writing Normal itself was not tried.

Which modes a car has is in the `SETTING_*_MODE_CONFIGURATION_SIGN` states (`0x48B06020…`, `0` = present).

## How the app does it

- One command, `drivemode set <number>`, run **inside the already running vehicle helper** (the ADB-shell process;
  the app's own process is refused by the car). It is not an entry of the command registry, so the denylist is
  untouched and the model is never offered it as a vehicle command.
- The command accepts only the numbers above for Normal, Eco, Sport, Snow, Sand, Mud, Mountain, Rock, Auto and
  Exit. Wade, warp, custom, traction and the U-turn are not offered, and anything else is refused before the car is
  touched.
- It first checks that the car's framework gives `SETTING_PRESELECTED_DRIVING_MODE_SET` the id `0x4C123030`, and
  that the car is **below 80 km/h**; if either fails nothing is sent.
- Like the car's screen, it sends nothing when the car is already in the mode, and "exit" while on the road has
  nothing to exit.
- After the write it reads the target for up to 2.5 s and reports what the car says it is in. If the car stayed
  where it was, the assistant says so rather than claiming success.
- The model sometimes asks for the same mode again straight after a success; a repeat within 6 s is answered from
  the first result without asking the car again.

## A second route: the classic operation mode

A car whose table does not name `SETTING_PRESELECTED_DRIVING_MODE_SET` (a DiLink 3.0 car's did not; its owner says it has drive modes,
and which ones is not known) may take the same request through the **Energy device**, device type 1006, which older cars use for
exactly this. The car's own `BYDAutoEnergyDevice` says how (read from the Leopard 5's framework, where the same class exists):

- `setOperationMode(value)` accepts 1 to 6 and takes the API's numbers, `ENERGY_OPERATION_ECONOMY` 1, `ENERGY_OPERATION_SPORT` 2,
  `ENERGY_OPERATION_NORMAL` 3, `ENERGY_OPERATION_SNOW` 4, `ENERGY_OPERATION_MUDDY` 5 and `ENERGY_OPERATION_SAND` 6. It turns the first
  three into the numbers of the drive-mode screen (3 becomes 1, 1 becomes 2, 2 becomes 3: Normal 1, Eco 2, Sport 3), leaves 4 to 6 as
  they are, and writes the result with `set(1006, ENERGY_OPERATION_MODE_SET, number)`.
- `getOperationMode()` returns `ENERGY_OPERATION_MODE` as it is, in the API's numbers (3 Normal, 1 Eco, 2 Sport, which is what the
  Leopard's state reads, see [drive-types.md](drive-types.md); 4 to 6 have not been seen).
- The class also has a terrain setting of its own (`setRoadSurfaceMode`, `ENERGY_ROAD_SURFACE_MODE`: common, snow, muddy, sand). A car
  may keep its terrain modes there and not in the operation mode; this build does not use it, and the log's `peek=` shows its state.

So the request is the screen's number, as on the first route (with Sand 5 and Mud 6 there, where the class's sand is 6 and its muddy
5, which the route keeps apart), and the state read back is turned into the screen's numbering to be compared with it. The route is
used when the car's table names `ENERGY_OPERATION_MODE_SET` and `ENERGY_OPERATION_MODE` (found by name, the car's own number is used),
and **only when the first route is not there**: the Leopard keeps the way it has always used. It asks for the six modes the class
takes (Normal, Eco, Sport, Snow, Sand, Mud; never Mountain, Rock, Auto or exit, which the class has no number for), under the same
80 km/h limit and with the same read-back, and the assistant is told those six. **Which of them the car has is the car's to say**:
a request it cannot follow leaves the state where it was and is reported as unchanged, not as done. Where the car's Energy class
refuses the helper for want of `BYDAUTO_ENERGY_COMMON`, or the car has no such class (or not the method asked for), the helper goes to
the native service, as for the first route.

**Status: not confirmed on a car.** Nobody has written `ENERGY_OPERATION_MODE_SET` yet, on the Leopard or anywhere; it has only run
in the simulator, against the car's own energy class (the class wrote 2, 3 and 1 to the feature for Eco, Sport and Normal, and 6, 5 and
4 for Sand, Mud and Snow, and the fake car's state followed), through the class and through the native service. The DiLink 3.0 owner has offered to try it; the answer
says what the car reports afterwards, and a car that takes the request and does not move is reported as unchanged and not as done.

To try it on the Leopard, which has both routes and keeps its own by default, parked:
`tools/check-native-route.sh <adb serial> --classic` (it sets `debug.bydang.drivemode` to `operation`, which only ever picks a route
the car's table names, runs the drive mode check and puts the switch back to `auto`).

## Scope

The write is in the motor/engine class of features that the app otherwise keeps unreachable. It is a **single,
deliberate exception** (with its second route for older cars, one more device, one more feature, three values): one device, one
feature id, fixed values, and it is only available when the car names the feature (as verified, on the first route), and the user has switched it on under
*Settings → Advanced → Experimental features* (off by default). Changing the drive mode changes how the car
delivers power, handles and brakes; the screen's own conditions still apply, but use it only when it is safe.
