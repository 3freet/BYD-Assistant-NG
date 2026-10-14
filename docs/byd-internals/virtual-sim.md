# The built-in SIM: virtual SIM, not a GSMA eSIM

How the head unit's mobile data SIM is provisioned and attached to the modem. The short version: this car has **no
GSMA eUICC and no Android eSIM (LPA) stack**. What BYD calls its "vSIM" is a **software SIM** that Android emulates
and feeds to the modem over a socket. Mostly **read-only** notes; nothing here was written to the car.

> As everywhere in these notes: observed on one vehicle and one firmware (a Fangchengbao Leopard 5, DiLink 5.1,
> MediaTek modem). Read from the system apps' code and the live properties. The vendor RIL (the part that turns
> these requests into modem AT commands) is not readable from the shell user, so that last hop is **not** documented.

## What is on the car

| Piece | Package / file | Role |
| --- | --- | --- |
| VsimService | `com.byd.vsimservice` (priv-app, uid phone, persistent) | Decides physical SIM or virtual SIM, drives the modem side |
| Identity server | `com.xinsheng.videntityserver` (priv-app, uid phone, persistent), `libsoftsim.so`, `libnativeutils.so` | Downloads and stores the profile, **is** the SIM: answers reset and APDUs |
| Adaptor client | `/system/lib64/libvsim-adaptor-client.so` | JNI: a local socket named `vsim-adaptor` towards the MediaTek RIL |
| Modem | MediaTek MOLY NR16 | Sees a "remote SIM" |

Android itself reports one physical slot (`isEmbedded=false`), no `android.hardware.telephony.euicc` feature and no
euicc service. The subscription list shows only ordinary SIMs.

## Activation

1. **Switch**: `persist.sys.byd.vsim.config` is "0" only when the feature is off; unset means enabled.
2. **Profile**: when no profile exists the identity server loops (every 10 s, hostname and fixed IP alternating)
   until a 20-character ICCID shows in `persist.radio.byd.vsim.iccid` or `ril.csim.iccid`, then does a plain HTTP GET
   to the SIM vendor's write server with `iccid`, `sessionId`, `timestamp` and a DES-MAC (`genMAC`) over them. The
   JSON answer is `{code, data:{instruction, mac}}`. `code` 0 hands `instruction` and `mac` to
   `NativeUtils.profileDownload`, which verifies and writes the profile. A second GET reports the result
   (status, errcode). `2104` (ICCID unknown) and `3009` (already downloaded) stop the loop.
3. **Profile storage**: a `profile` file under `/collect2/vsim/`, mirrored in the app's own data directory.
   `NativeUtils.profileExisted()` is the test; state also in `persist.sys.byd.vsim.state` (0 or 1).
4. **Attach**: VsimService starts at boot, and 500 ms later calls `profileExisted()`:
   - profile exists: `card_power(1)`, connect, then (first time) `platformCapability`, `vSimPersist`, `vSimEnable`,
     then `vSimPlugIn`, and sets `persist.sys.byd.vsim.state` = 1. A thread then answers the modem's requests;
     on any failure it waits 10 s and reconnects.
   - no profile: `vSimDisable`, state = 0 (the physical SIM stays in use).

## Reactivation

- `IVsimService` (action `com.byd.vsimservice.aidl.VsimServiceMain`, descriptor `com.byd.vsimservice.IVsimService`):
  1 `switchVsim(slot)` re-runs the attach above, 2 `switchPhysicalSim(slot)` sends `vSimDisable`,
  3 `vsimServiceCheckProfile()`. The slot defaults to 1. The service is exported and its manifest declares no
  permission for it.
- The same check runs on every start of the service, so a reboot re-attaches.
- `IVidentityService` (binder name `VIdentityService`, transactions 1 `card_connect`, 2 `apdu_transmit`,
  3 `card_power`, 4 `profileExisted`, 5 `profileDelete`, 6 `getVersion`): `profileDelete` removes the profile; the
  service then downloads again on its next start.

## Commands to the modem

Frame to and from the `vsim-adaptor` socket (reserved namespace, stream), JSON on the Java side:
`{"transaction_id", "slotId", "message_id", "data"}`. Binary header: transaction id, message id, slot id, length
(314-byte payload).

| JNI call | message id | event | Meaning |
| --- | --- | --- | --- |
| `connect()` | | | open the socket (only when `gsm.radio_state` is 1, 20 tries 1 s apart) |
| `platformCapability(fd, slot, v)` | 2 | | ask what the platform supports (`vendor.gsm.modem.vsim.capability`, 3 here) |
| `vSimEnable(fd, slot, 1)` | 3 | 1 | enable the remote SIM |
| `vSimDisable(fd, slot, 1)` | 3 | 2 | disable it (back to the physical SIM) |
| `vSimPlugOut(fd, slot, v)` | 3 | 3 | take the card out |
| `vSimPlugIn(fd, slot, 1)` | 3 | 4 | insert the card |
| `vSimPersist(fd, slot)` | 3 | 5 (value 1) | keep it across modem resets |
| `vSimGetState(...)` | 7 | | state query |
| `receiveATRAPDU` | in: 1004 reset, 1005 APDU | | request from the modem |
| `sendVSimATRAPDU` | out: 4 | | ATR (for 1004) or R-APDU (for 1005), transaction id echoed |

For 1005 the service sends the APDU to `apdu_transmit`; while the status word is `61xx` it repeats with
`00 C0 00 00 xx` (GET RESPONSE) and returns the last data. The identity server's `libsoftsim.so` is the card
(a USIM with a toolkit and secured-packet handling).

The Android framework's own path is different and visible in `vendor.mediatek.hardware.mtkradioex.sim`:
`setSimPower`, `activateUiccCard`, `deactivateUiccCard`, `sendVsimNotification`, `sendVsimOperation`,
`getCurrentUiccCardProvisioningStatus`. How the RIL maps either path to AT commands was not readable.

## State of this car

`persist.sys.byd.vsim.state` is 0, `VIdentityService.profileExisted()` returns false (a read-only binder call), and the
subscription is a physical SIM (ICCID prefix 898609, PLMN 46009, roaming on a local operator). So the virtual
SIM is installed but unused: **no profile is present, the physical SIM carries the data**.

## Not known

- The AT commands behind the adaptor socket.
- Whether the download thread is running now and what the server answers for this ICCID (no log for the tags was
  left in the buffer).
- The profile's structure (the files are in `/collect2` and the app's data directory, unreadable from the shell).
