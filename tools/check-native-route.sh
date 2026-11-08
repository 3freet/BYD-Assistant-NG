#!/usr/bin/env bash
# Checks the helper's native route (a call to the car's own service instead of its device classes, see
# docs/byd-internals/hal-and-permissions.md#the-native-route) on a car whose device classes accept the helper.
#
#   tools/check-native-route.sh <adb serial, e.g. 192.168.0.20:5555>           read-only: the helper's own check of the answers
#   tools/check-native-route.sh <adb serial> --write                           also one reversible write through the route
#   tools/check-native-route.sh <adb serial> --drive                           also the drive mode through the route, then back
#   tools/check-native-route.sh <adb serial> --classic                         the same drive mode check, but through the classic Energy operation mode
#                                                                              (Eco, Normal, Sport; docs/byd-internals/drive-modes.md), on a car that has both routes
#   (the options can be given together)
#
# It needs the debug build (package com.bydassistantng.dev) on the car and `adb` connected to it. The read-only check changes
# nothing. --write switches the driver's seat heating to low for a few seconds and back off: run it parked, with someone in the
# car who can see the seat's switch. --drive asks for the Eco drive mode (Normal if the car is in Eco) and then for the mode the
# car was in; it is the app's drive mode request with its checks (the listed modes, the speed limit), so it does nothing at 80 km/h
# or more: run it parked too. --classic is --drive with the test switch debug.bydang.drivemode set to `operation`, which puts a car that
# has the Energy operation mode (the Leopard 5 does) on that route instead of its own; it never puts a car on a route it does not have.
# Afterwards the test switches (debug.bydang.nativeroute, debug.bydang.drivemode) are put back to auto.
set -u
SERIAL=${1:?usage: check-native-route.sh <adb serial> [--write] [--drive] [--classic]}
shift
WRITE=0
DRIVE=0
CLASSIC=0
for option in "$@"; do
  case $option in
    --write) WRITE=1 ;;
    --drive) DRIVE=1 ;;
    --classic) DRIVE=1; CLASSIC=1 ;;
    *) echo "unknown option $option (--write, --drive, --classic)" >&2; exit 2 ;;
  esac
done
PKG=com.bydassistantng.dev
a() { command adb -s "$SERIAL" "$@"; }
applog() { a shell "run-as $PKG cat files/app_log.txt" 2>/dev/null | tr -d '\r'; }
tool() { # <family> <json arguments>
  a shell "am broadcast -a com.bydassistantng.debug.TOOL -n $PKG/com.bydassistantng.debug.DebugToolReceiver --es tool $1 --es args '$2' --ez carlink true --ez newturn true" >/dev/null
  sleep 5
}
restore() { a shell setprop debug.bydang.nativeroute auto >/dev/null 2>&1; a shell setprop debug.bydang.drivemode auto >/dev/null 2>&1; }
trap restore EXIT

a get-state >/dev/null 2>&1 || { echo "adb cannot reach $SERIAL: adb connect it first" >&2; exit 2; }
a shell "pm list packages $PKG" | grep -q "$PKG" || { echo "$PKG is not installed on the car (./gradlew :app:installDebug)" >&2; exit 2; }

echo "== starting the app and the helper (a read of the cabin temperature)"
a shell setprop debug.bydang.nativeroute auto
# A new app process, so that the helper's line is written to the log again (it is written once per run of the app).
a shell "am force-stop $PKG" >/dev/null 2>&1 || true
a shell "pkill -f VehicleShellHelper" >/dev/null 2>&1 || true
before=$(applog | wc -l)
a shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 6
tool read_car '{"what":"inside_temperature"}'
sleep 3
fresh() { applog | tail -n +$((before + 1)); }

line=$(fresh | grep "Vehicle helper found" | tail -1)
if [ -z "$line" ]; then
  echo "the helper's line is not in the app log yet: open the app, say something to start a conversation, and run this again" >&2
  exit 3
fi
echo "$line" | grep -o -E "(services|acroute|native|names)=[^ ]*" | sed 's/^/   /'
native=$(echo "$line" | grep -o -E "native=[^ ]*" | head -1)
reads=$(echo "$native" | grep -o -E "reads:[0-9]+/[0-9]+" | head -1)
agree=$(echo "$native" | grep -o -E "agree:[0-9]+/[0-9]+" | head -1)
if [ -z "$native" ] || [ "$native" = "native=-" ]; then
  echo "RESULT: the car has no native service the helper can speak to (native=- or missing)"; exit 1
fi
r_ok=${reads#reads:}; r_ok=${r_ok%%/*}; r_all=${reads#*/}
g_same=${agree#agree:}; g_same=${g_same%%/*}; g_cmp=${agree#*/}
echo "   the service answered $r_ok of $r_all states; where the device classes also answered it agrees on $g_same of $g_cmp"
took=$(echo "$native" | grep -o -E "ms:[0-9]+" | head -1)
[ -z "$took" ] || echo "   the helper's start-up check took ${took#ms:} ms"
if [ "$r_ok" -lt $((r_all - 3)) ]; then echo "RESULT: FAIL, the service answered too few reads"; exit 1; fi
if [ "$g_cmp" -gt 0 ] && [ "$g_same" -lt $((g_cmp - 2)) ]; then echo "RESULT: FAIL, the answers differ from the device classes' (the reply is read wrongly)"; exit 1; fi
if [ "$g_cmp" -eq 0 ]; then echo "   (nothing to compare with: the device classes refuse the helper here, so only the reads' plausibility can be judged)"; fi
echo "RESULT: the read-only check passed"

if [ "$WRITE" = 1 ]; then
  echo "== one write through the route: the driver's seat heating, low then off"
  a shell setprop debug.bydang.nativeroute always
  a shell "pkill -f VehicleShellHelper" >/dev/null 2>&1 || true
  mark=$(applog | wc -l)
  tool seat '{"setting":"driver.heating","value":"low"}'
  sleep 3
  tool seat '{"setting":"driver.heating","value":"off"}'
  sleep 3
  applog | tail -n +$((mark + 1)) | grep -E "VehicleDispatch\] dispatch seat" | sed -E 's/^[0-9-]+ [0-9:.]+ /   /'
  if applog | tail -n +$((mark + 1)) | grep -E "dispatch seat.driver.heating\(value=low.*confirmed.*\[native\]" >/dev/null; then
    echo "RESULT: the write went through the car's service and the car confirmed it"
  else
    echo "RESULT: FAIL, the write did not come back confirmed through the route (see the lines above)"; exit 1
  fi
fi

if [ "$DRIVE" = 1 ]; then
  echo "== the drive mode through the route: Eco (Normal if the car is in Eco), then back to the mode the car was in"
  a shell setprop debug.bydang.nativeroute always
  if [ "$CLASSIC" = 1 ]; then
    echo "   (on the classic route: the Energy operation mode, Eco / Normal / Sport)"
    a shell setprop debug.bydang.drivemode operation
  fi
  a shell "pkill -f VehicleShellHelper" >/dev/null 2>&1 || true
  mark=$(applog | wc -l)
  drive_line=""
  ask_drive() { # <mode word>: asks the car for it as the assistant does, waits for the app's line about it and shows the line
    a shell "am broadcast -a com.bydassistantng.debug.TOOL -n $PKG/com.bydassistantng.debug.DebugToolReceiver --es tool set_drive_mode --es args '{\"mode\":\"$1\"}' --ez carlink true --ez newturn true" >/dev/null
    drive_line=""
    for _ in $(seq 1 25); do
      drive_line=$(applog | tail -n +$((mark + 1)) | grep -F "[DriveMode] Drive mode $1 " | tail -1)
      [ -n "$drive_line" ] && break
      sleep 1
    done
    mark=$(applog | wc -l)
    echo "$drive_line" | sed -E 's/^[0-9-]+ [0-9:.]+ /   /' | cut -c1-240
  }
  word_for() { # the car's number for a drive mode -> the word the assistant uses; nothing for a mode that cannot be asked for
    case $1 in 1) echo normal ;; 2) echo eco ;; 3) echo sport ;; 4) echo snow ;; 5) echo sand ;; 6) echo mud ;; 7) echo mountain ;; 8) echo rock ;; 21) echo auto ;; esac
  }
  ask_drive eco
  [ -n "$drive_line" ] || { echo "RESULT: FAIL, the app logged no answer to the drive mode request"; exit 1; }
  was=$(echo "$drive_line" | grep -o -E "note=before=[0-9]+" | grep -o -E "[0-9]+$" | head -1)
  [ -n "$was" ] || { echo "RESULT: FAIL, the request was not carried out (a refusal, or the car is at the speed limit): see the line above"; exit 1; }
  if [ "$was" = 2 ]; then
    echo "   (the car was already in Eco, so that sent nothing: asking for Normal)"
    ask_drive normal
    back=eco
  else
    back=$(word_for "$was")
  fi
  if echo "$drive_line" | grep -q "\[native\]" && echo "$drive_line" | grep -q "=> Changed("; then
    echo "RESULT: the drive mode was asked for through the car's service and the car changed to it"
    ok=1
  else
    echo "RESULT: FAIL, the car did not report the new mode through the route (see the line above)"
    ok=0
  fi
  if [ -n "$back" ]; then
    echo "   putting it back: $back"
    ask_drive "$back"
  else
    echo "   the car was in mode $was, which the assistant cannot ask for: put it back on the car's screen"
  fi
  [ "$ok" = 1 ] || exit 1
fi
