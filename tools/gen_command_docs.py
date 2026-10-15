#!/usr/bin/env python3
"""Regenerates docs/byd-internals/vehicle-commands.md from the command registry.

    python3 tools/gen_command_docs.py

The registry (app/src/main/resources/vehicle_commands.json) is the single source of truth; this only
formats it, so the table can never disagree with what the app actually sends.
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "app/src/main/resources/vehicle_commands.json"
OUT = ROOT / "docs/byd-internals/vehicle-commands.md"

DEVICES = {1000: "Ac", 1001: "Bodywork", 1002: "Audio", 1004: "Light", 1023: "Setting", 1010: "Security"}

# What has been confirmed on a real vehicle. Anything not listed here and without a state id to read back
# is "accepted by the HAL only".
NOTES = {
    "steeringWheel.heating": "Has a read-back, but this control has not been exercised on a vehicle yet.",
    "ambient.color": "Sets the whole cabin (front and back rows). The index is the car's 1–127 colour strip; the names are the nearest of the lamp's measured output colours. The read-back is the front row.",
    "ambient.brightness": "Whole cabin. The method takes 0–5 and the car's state reads 1–6 (the value shown here).",
    "ambient.dynamic": "On is the animated multi-colour scene; a fixed colour is easiest to see with it off.",
    "mirrors.autoFold": "The 'Side mirror auto fold' switch on the Driving screen. A manual fold or unfold command was not found.",
    "mirrors.tiltInReverse": "The 'Side mirror auto flip while reversing' switch. Off is written as 2 and reads back 0.",
    "audio.mediaMute": "Mutes media only (1 = normal, 2 = muted). The all-sound mute (`AUDIO_MUTE_STATUS_SET`) also works but would silence the assistant.",
    "audio.navigationVolume": "The HAL value confirms; whether the navigation app's voice follows it was not heard.",
    "audio.soundFocus": "The Devialet Sound Staging choice: whole car 6, front 7, back seats 4 (as the screen wrote them).",
    "audio.spaceSound": "Devialet Space switch: off 1, on 2.",
    "audio.spaceSoundMode": "Devialet Space strength: soft 1, standard 2, strong 3.",
    "audio.headrestSound": "The Devialet Headrest Sound dialog: surround 2, interactive 3, exclusive 4; 1 (off) is accepted by the car but the screen has no button for it. This car has only the driver's headrest speaker.",
    "audio.soundStyle": "The Devialet Sound Features dialog: fidelity 1, soft 2, dynamic 3, heavy 4, speech 5, custom 6.",
    "audio.volume": "The car's volume is Android's media stream (0–39 here); the older `AUDIO_VOLUME_SET` feature was accepted and ignored. Confirmed by reading the stream back.",
    "dashcam.record": "The answer is the dashcam service's own verdict (1 = done). Stopping is refused for a few seconds while a locked clip is being saved.",
    "dashcam.photo": "The answer is the dashcam service's own verdict (1 = done). Brings the dashcam screen to the front.",
    "dashcam.lock": "The answer is the dashcam service's own verdict (2 or 3 = saving). Brings the dashcam screen to the front.",
    "dashcam.audio": "The answer is the dashcam service's own verdict (1 = done); the dashcam shows its own notice.",
    "dashcam.collisionSensitivity": "The answer is the dashcam service's own verdict (1 = done); the dashcam's settings screen follows.",
    "seat.driver.massage": "Off is a write of 1 to the mode control (0 is ignored); on starts at the chosen level.",
    "seat.passenger.massage": "Off is a write of 1 to the mode control (0 is ignored); on starts at the chosen level.",
    "fridge.cool.temperature": "Screen −6…6 °C is signal 13…25 (offset 19). Refused unless the fridge is cooling.",
    "fridge.heat.temperature": "The screen value equals the signal (35…50). Refused unless the fridge is heating.",
    "fridge.mode": "Switching to cooling starts at 3 °C, to heating at 50 °C.",
    "ac.auto": "Only 'auto' can be requested; any manual change takes the A/C out of auto by itself.",
    "ac.ventilation": "On also forces outside air and switches the compressor off; off returns the A/C to auto.",
    "ac.frontDefrost": "On forces fan 7, face/defrost vents and outside air; off falls back to auto.",
    "ac.airSource": "0 = outside (fresh) air, 1 = recirculate.",
    "ac.windMode": "Values 1–5 are as observed; the car also reports 7 for a mode not covered here.",
}


def values(c):
    p = c["parameter"]
    if p["type"] == "fixedEnum":
        return ", ".join(f"`{k}`={v}" for k, v in p["options"].items())
    unit = f" {p['unit']}" if p.get("unit") else ""
    shown = f"{p['min']}…{p['max']}{unit}"
    return shown + (f" (signal = value {c['valueOffset']:+d})" if c.get("valueOffset") else "")


def route(c):
    inv = c["invocation"]
    if inv["type"] == "binderCommand":
        return f"`{inv['serviceName']}` service, command {inv['commandCode']}"
    if inv["type"] == "streamVolume":
        return f"Android audio stream {inv['stream']} (`AudioManager.setStreamVolume`)"
    if inv["type"] == "acBinderProperty":
        return f"A/C service, property {inv['propertyId']}, area {inv['area']}"
    if inv["type"] == "namedMethod":
        return f"`{inv['deviceClass']}` method `{inv['methodName']}`"
    dev = DEVICES.get(c.get("deviceType"), str(c.get("deviceType")))
    return f"{dev} device ({c.get('deviceType')}), control id {c['featureId']}"


def state(c):
    inv = c["invocation"]
    if inv["type"] == "binderCommand":
        return "the service's own answer"
    if inv["type"] == "streamVolume":
        return "the stream volume, read back"
    if inv["type"] == "acBinderProperty":
        return "read back through the A/C service"
    if c.get("stateFeatureId"):
        return f"state id {c['stateFeatureId']}"
    return "—"


def status(c):
    inv = c["invocation"]
    if c["id"] in NOTES:
        return NOTES[c["id"]]
    if inv["type"] in ("acBinderProperty", "binderCommand", "streamVolume") or c.get("stateFeatureId"):
        return "Confirmed by read-back."
    return "Accepted by the HAL; no read-back, so not independently verified."


rows = json.loads(REGISTRY.read_text(encoding="utf-8"))
lines = [
    "# Vehicle controls the app can operate",
    "",
    "_Generated by `tools/gen_command_docs.py` from `app/src/main/resources/vehicle_commands.json` — do not edit by hand._",
    "",
    "Everything here was observed on **one vehicle and firmware**. A control that works there may be missing or",
    "behave differently on another model. The *Read-back* column says whether the car itself confirms the result;",
    "entries without one (the open/close style controls, sunroof, trunk, volume, screen rotation) are known to be",
    "accepted by the HAL but were not independently verified. Values are what the user and the model deal in;",
    "where the car's own signal differs, the offset is shown.",
    "",
    "| Command | Reaches the car through | Values | Read-back | Notes |",
    "| --- | --- | --- | --- | --- |",
]
for c in rows:
    lines.append(f"| `{c['id']}` — {c.get('label', c['displayName'])} | {route(c)} | {values(c)} | {state(c)} | {status(c)} |")
lines += [
    "",
    f"{len(rows)} commands. Domains that affect driving (engine, gearbox, motor, driver assistance, radar, security, power)",
    "are deliberately absent: the registry refuses to load an entry in one of them. See [safety](../../README.md#safety).",
    "",
]
OUT.write_text("\n".join(lines), encoding="utf-8")
print(f"wrote {OUT.relative_to(ROOT)} ({len(rows)} commands)")
