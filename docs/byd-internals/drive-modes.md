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

## Scope

The write is in the motor/engine class of features that the app otherwise keeps unreachable. It is a **single,
deliberate exception**: one device, one feature id, fixed values, and it is only available when the build has the
experimental car features, the car names the feature as verified, and the user has switched it on under
*Settings → Advanced → Experimental features* (off by default). Changing the drive mode changes how the car
delivers power, handles and brakes; the screen's own conditions still apply, but use it only when it is safe.
