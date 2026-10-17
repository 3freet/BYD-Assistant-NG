# Bluetooth: what the head unit does, and what it does not see

Observed on one vehicle and one firmware, parked. The question: when the owner's phone controls the car over a direct
Bluetooth connection (no cloud), what does the head unit take part in? Short answer so far: **very little that we can
see.** What is known, and what is not, is below.

## What the head unit's own Bluetooth does

- It is a **hands-free and audio sink** for the phone: the stack's connection policy is on for `HEADSET_CLIENT` and
  `A2DP_SINK` and off for the other profiles. (`dumpsys bluetooth_manager`.)
- **`com.byd.bluetoothleservice`** (a system app, shared uid `android.uid.system`) is a **music helper**, not a key. It
  advertises one Bluetooth LE service and runs a GATT server whose characteristics carry a heartbeat and "now playing"
  status for the NetEase Cloud Music app (the app's code names them `...MUSICSTATUS` and `...NETEASE_MUSIC`), plus an
  RFCOMM server socket for Bluetooth music. It reads nothing about the car.
- Two things scan or connect over Bluetooth LE at any time: system-uid apps registering a scan for the 16-bit service
  `0xFC55` (which of them was **not** identified; it is not in the music helper or the other BYD Bluetooth, phone-link
  and remote apps that were searched), and an app the owner installed, which reconnects to its own device every 20 s
  (this shows in every recording as `GATT_Register` / `GATT_Deregister` and is not related to the car).

## What the car's framework names for a Bluetooth key

The car's own id list (`BYDAutoFeatureIds`) holds a family of ids that all begin `0x3E8…` and read like the signals of a
phone key:

| Id | Hex | Group | Value read at rest |
| --- | --- | --- | --- |
| `BODYWORK_BT_UNLOCK` | `0x3E805000` | Bodywork | 1 |
| `BODYWORK_BLUETOOTH_UNLOCK_TYPE` | `0x3E805010` | Bodywork | 0 |
| `BODYWORK_BT_KEY_CODE` | `0x3E8FA010` | Bodywork | 1 |
| `LIGHTS_FLASHING_LIGHT_AND_HORN` | `0x3E800008` | Light | 65535 (no reading) |
| `BODY_DISUS_BLUETOOTH_CONTROL_ORDER` (and `_SINGLE`) | `0x3E82A010`, `0x3E82E010` | Bodywork | 65535 |
| `BODY_MUSIC_SELECT` | `0x3E82A014` | Bodywork | 65535 |
| `SETTING_SCENE_MODE_BLUETOOTH_CONTROL_COMMAND` | `0x3E82B010` | Setting | 65535 |
| `BODYWORK_ONLINE_HAS_0x03E80A`, `..._0x03E816` | `0x3E80A000`, `0x3E816000` | Bodywork | 65535 |

Around them: the setting `SET_CMD_BT_KEY_MAC` (a phone-key address, reads `-10013`), the setting
`SETTING_BLUETOOTH_UNLOCKING_LEFT_FRONT_DOOR_LINKAGE`, and a parallel NFC-key family (`BODYWORK_NFC_KEY_UNLOCK_SRC` and
others). The shared prefix and the names are what suggest a Bluetooth-key module whose messages the head unit's HAL
can report; nothing here proves it.

**`LIGHTS_FLASHING_LIGHT_AND_HORN` belongs to this family**, which fits its behaviour: it has no value to read, and a
write of 1, 2 or 3 is refused with `BYDAUTO_COMMAND_RESULT_FAILED`. Read as a *report from the key module*, not a
command to the lights, that is what one would expect. See [findings-and-dead-ends.md](findings-and-dead-ends.md).

## What a recorded Bluetooth "find my car" showed

The phone's "find my car", sent over a direct Bluetooth connection, was recorded for 100 s with the polling probe (all
about 3,700 readable states) and the system log (`-b all`, no gaps over 0.25 s):

- The lights did what they do for the cloud trigger: turn signal 1 → 9 and both daytime-light states 1 → 2 for 11.3 s.
- **None of the Bluetooth-key states above changed**, and **no event with an id starting `0x3E8` was posted**, in either
  the cloud or the Bluetooth recording. (`BODYWORK_BT_KEY_CODE` and `BODYWORK_BT_UNLOCK` stayed at their values.)
- The cloud daemon logged nothing, and the two seconds before the first light event held only a periodic modem
  temperature poll that is not new (a control: the same comparison picks out the cloud daemon's messages for the cloud
  trigger at once).

So on this route the head unit is told the result, the lights, and nothing about the request. Either the key module
talks straight to the body electronics and the head unit is only a bystander, or it reports on a path that neither the
system log nor the polled states show. Either way no Android-side trace of the request exists to listen to.

## What a lock and an unlock from the phone showed

Recorded the same way (2 minutes; the owner pressed lock, then unlock, on the phone). The car reports a lock or unlock
to the head unit much more fully than it reports "find my car":

| Time | What the owner did | What the head unit's framework posted |
| --- | --- | --- |
| 19:27:02.85 | lock | `BODYWORK_NFC_KEY_UNLOCK_SRC` (`0x4036B020`) = **35** and `..._EXECUTE_RESULT` (`0x4036B030`) = 0; 0.15 s later the three door states 1 → 2; `BODYWORK_WHOLE_VEHICLE_LOCK_TYPE` 12 → 5; `BODYWORK_SMART_VOICE_LIMIT` 0 → 1. No light change. |
| 19:27:09.10 | unlock | `BODYWORK_BT_UNLOCK` (`0x3E805000`) = 1 and `BODYWORK_BLUETOOTH_UNLOCK_TYPE` (`0x3E805010`) = 0, **posted four times about 100 ms apart**; `BODYWORK_NFC_KEY_UNLOCK_SRC` = **34**; the door states 2 → 1; then `BODYWORK_BT_KEY_CODE` (`0x3E8FA010`) = 1 three times about 100 ms apart; a short flash of the turn signals and daytime lights (1.45 s). |

- The `0x3E8…` events appear **only around the unlock**, with the values they read at rest, so the polling probe cannot
  see them (nothing changes); only the system log's `postEvent` lines show them. They were absent at the lock and at every
  "find my car" recorded.
- `BODYWORK_NFC_KEY_UNLOCK_SRC` looks like the **source of the lock or unlock** (35 for this lock, 34 for this unlock; 32
  at rest), whatever its "NFC" name. The values are not named in the framework. Pressing lock and unlock from other
  sources (the fob, the door button, the NFC card, the phone through the cloud) with the same recording would show which
  number is which.
- The cloud daemon did not receive a request for either: its only request in the recording was a routine one at
  19:26:41. It *consumes* the events the car posts, reports them to the server (its handlers log "OTA_LF_DOOR_LOCK
  unlock to lock", "recv 3e8 05 ... set_unlock_type"), and the server answers with acknowledgements.
- The head unit's own `CarLockSoundControl` listens to the lock events, which is what plays the lock chime.
- Two further 11.3 s flashes (turn signals 9, daytime lights 2) happened at 19:27:23.9 and 19:27:39.2, with no key,
  lock or cloud event before either, exactly like the Bluetooth "find my car" before. They are what a find-my-car press
  looks like to the head unit; what the owner pressed at those moments is not recorded here.

## What is not known

- Which component answers the phone's Bluetooth connection for key functions. It is not an app on the head unit that was
  found, and the head unit's Bluetooth stack has no profile for it.
- Which number of `BODYWORK_NFC_KEY_UNLOCK_SRC` belongs to which source (see above).
- Whether anything the head unit can write would lock or unlock the doors through the same path. Nothing was written.
  `SET_CAR_DOOR_LOCK` reads `-10011` (not available), which says only that it is not readable.
- What `0xFC55` is, and which system app scans for it.
- What the `DiSus` control order and the scene-mode command are for. They are not to be written to: the first looks
  like a suspension request.
