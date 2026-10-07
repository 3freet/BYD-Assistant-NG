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
| [steering-wheel-button.md](steering-wheel-button.md) | How the steering-wheel microphone button reaches Android, and keeping it alive across sleep and wake |
| [research-method.md](research-method.md) | How to find a control and learn what its values mean, with the read-only probes in [`tools/probes`](../../tools/probes) |
| [findings-and-dead-ends.md](findings-and-dead-ends.md) | Quirks that cost time, and the things that could **not** be done (the horn, the security group) |
| [vehicle-commands.md](vehicle-commands.md) | Every control the app can operate, generated from the registry the app uses |

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
