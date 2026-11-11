# The steering-wheel microphone button

## How the press reaches Android

The button is not a standard Android key.

- It arrives from the kernel input device **`simulate-keys`** (`/dev/input/event0`).
- The unit's key layout maps the kernel scan codes **290 and 312** to Android key codes **320 (short press)**
  and **328 (long press)**. Neither has a `KeyEvent.KEYCODE_*` constant.
- The window manager logs it (`interceptKeyTq keycode=320`), and `getevent -l` shows the scan codes.

## Capturing it

An **accessibility service** that requests `flagRequestFilterKeyEvents` (with
`android:canRequestFilterKeyEvents="true"`) is handed every hardware key before any app sees it; returning
`true` from `onKeyEvent` consumes the key. This is how existing button-mapper apps for DiLink work, and it is
what this project does. The service asks for no window content and no events — key filtering only.

Things that behave differently from what you would expect:

- **Every** key-filtering service is notified, so another app's own mapping of the same key still fires. A
  mapper that maps 320 to something has to be told to stop.
- `adb shell input keyevent N` is injected *after* the filter and **bypasses it**, so it cannot be used to test
  the service. Only a real press (or a physical event) exercises it.
- On this unit there is **no Accessibility settings screen**: `android.settings.ACCESSIBILITY_SETTINGS` resolves
  to no activity. The service is switched on by appending it to `Settings.Secure.enabled_accessibility_services`,
  which needs `WRITE_SECURE_SETTINGS`. That permission can only be granted from ADB (`pm grant`), and the app
  does so itself over the loopback connection. Always *append*: the list already holds the system UI's own
  service and others.
- Running `uiautomator dump` temporarily **unbinds every other accessibility service** (they log "Connected"
  again afterwards). Do not dump the UI while waiting for a button press.

## Holding the button

With the live broadcast switched on (see [exterior-speaker.md](exterior-speaker.md#the-live-microphone-broadcast)) the button has a
second gesture: **held for 1 second it starts the broadcast**, and any press ends it. It is armed only while the setting, the exterior
speaker and vehicle control are all on and the assistant's trigger is the button's short press (the default); otherwise the button
behaves exactly as before. With the hold armed, a **short press acts when the button comes up**, not when it goes down, because a hold
cannot be told from a press until then (the assistant is not started by a press that turns into a hold).

`MicHoldDetector` reads the two key codes (320 and 328) without relying on when the unit sends which: a press seen going down is timed by
the service, and the unit's own long-press code counts as a hold too, at once when no press was seen going down and at the one-second mark
when one was. Each key event of the button is logged while the hold is armed (`Mic button key 320 down, repeat 0, held 0 ms`), which is
where to read how the unit really sends a hold. A hold during a conversation does nothing, and while a broadcast is being put away
(its last half second) presses are swallowed rather than starting the assistant.

## Staying alive across sleep and wake

"Waking" the head unit is a **quick-boot**, not a reboot: uptime keeps counting, but boot broadcasts are sent
again and the vendor's cleaner force-stops apps around it. Two separate failures followed from that.

1. **The service entry disappears.** Android removes an app's accessibility service from the enabled list
   whenever the app is force-stopped. Nothing puts it back, so the button is dead until the app is opened.
   Fix: on `BOOT_COMPLETED`, `QUICKBOOT_POWERON` and `MY_PACKAGE_REPLACED`, and again whenever the app
   process starts, re-append the entry.
2. **The entry stays but nothing is bound.** After some wakes the entry was still in the list yet no service
   process existed, because the system only binds an accessibility service when that setting *changes* —
   rewriting an identical value does nothing. Fix: remove the entry and add it again; the system starts the
   service within about a second.

Neither runs at all unless the vendor's **auto-start manager** lets the app start in the background — boot
broadcasts were only delivered to apps on its whitelist. The whitelist cannot be read by an app. Its screen
opens with the action `android.intent.action.BYD_APPSTARTMANAGEMENT`, and an app update can reset it, so the
user has to switch the app's toggle **off** there (the head unit starts the app by itself only when its toggle is off; the app links to the screen from Settings and from the first-time setup).

Debugging tip: `adb logcat -b events` (the events buffer) reaches back much further than the main log, and its
`am_proc_start` lines say *why* a process started (a broadcast, a service bind, the next top activity), which is
how the two failures above were told apart. `dumpsys accessibility` lists bound services across several
wrapped lines — search for the service label rather than reading the first line.
