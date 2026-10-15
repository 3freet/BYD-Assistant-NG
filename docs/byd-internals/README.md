# BYD DiLink internals — what we learned and how

These notes document how an ordinary Android app can read and operate parts of a **BYD DiLink head unit**,
without root, and how the controls in this project were found. They exist so that anyone working on a BYD
head unit — this project or another — does not have to rediscover them.

> **Read this first.**
> Everything here was **observed** on one vehicle and one firmware by probing the head unit's own system
> classes and services. None of it is official or documented by BYD, none of it is guaranteed to hold on
> another model or after a software update, and some of it will be wrong for your car. Treat every id and
> value as a hypothesis until you have read it back on your own vehicle. Operating vehicle systems can cause
> harm: experiment only while parked, never touch systems that affect driving, and accept that you do so at
> your own responsibility.

## Where this came from

All of it comes from the head unit itself: its framework classes, its system services, its own logs, and
watching what the car's native screens do while they are operated. **Nothing is copied from another app's
data or from leaked documents**, and contributions should keep it that way (see
[CONTRIBUTING.md](../../CONTRIBUTING.md)).

## Contents

| Document | What it covers |
| --- | --- |
| [hal-and-permissions.md](hal-and-permissions.md) | The `android.hardware.bydauto` classes, device types, control vs state ids, the permission model and why a helper process running as the ADB shell user is needed |
| [ac-service.md](ac-service.md) | The air-conditioning Binder service: wire format, property ids, areas, observed values and side effects |
| [tyres.md](tyres.md) | Reading tyre pressure: the tyre and instrument devices, wheel areas, how the units were worked out |
| [steering-wheel-button.md](steering-wheel-button.md) | How the steering-wheel microphone button reaches Android, and keeping it alive across sleep and wake |
| [research-method.md](research-method.md) | How to find a control and learn what its values mean, with the read-only probes in [`tools/probes`](../../tools/probes) |
| [findings-and-dead-ends.md](findings-and-dead-ends.md) | Quirks that cost time, and the things that could **not** be done (the horn, the security group) |
| [vehicle-commands.md](vehicle-commands.md) | Every control the app can operate, generated from the registry the app uses |
| [exterior-speaker.md](exterior-speaker.md) | Sending sound to the speaker outside the car: the switch, the navi stream, how loudness really works, and why a process holds the switch |
| [drive-modes.md](drive-modes.md) | How the car's own screen selects Snow, Sand, Mud…, the numbers it sends, the read-back, and what the app checks first |
| [drive-types.md](drive-types.md) | How the head unit tells front, rear and all-wheel drive apart, which signal names the working axle, and what Eco, Normal and Sport did on a drive |
| [cabin-and-dashcam.md](cabin-and-dashcam.md) | Air quality, the ambient light strip, mirror switches, audio and sound staging, and the dashcam's command service: ids, values, how each was found and what did not work |
| [passenger-screen.md](passenger-screen.md) | The second (FSE) screen: its display, the separate Android user, launching apps on it, and the controls the car names for it |
| [virtual-sim.md](virtual-sim.md) | The built-in "eSIM": a software SIM, how its profile is downloaded and attached to the modem, the switch calls and the socket commands |

## The short version

1. Vehicle data and controls live behind `android.hardware.bydauto.*` classes in the framework. Each device
   class wraps two generic calls — `get(deviceType, id)` and `set(deviceType, ids, values)` — over a large
   space of numeric feature ids.
2. The vehicle permissions are signature-level, so an ordinary app is refused. The **ADB shell identity is
   accepted**, which is why the app runs a small helper process through a loopback ADB connection.
3. A control id (`…_SET`) and the state it changes (the same name without `_SET`) are different ids. Success is
   only real when the **state is read back**; a `set` that returns 0 only means "accepted".
4. The air conditioner is different: it is its own Binder service with its own, much smaller id space.
5. To learn a control, operate it on the native screen while a read-only recorder logs every state that
   changes. That is how every control in [vehicle-commands.md](vehicle-commands.md) was found.
