#!/usr/bin/env bash
set -euo pipefail

export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
ADB="adb -s emulator-5554"
AVD="${E2E_AVD:-agentbus}"

# A stale emulator holds the AVD lock; drop it before starting a fresh one.
if adb devices | grep -q '^emulator-5554'; then
  adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
  sleep 3
fi

"$ANDROID_HOME/emulator/emulator.exe" -avd "$AVD" -port 5554 \
  -no-window -no-snapshot -noaudio -no-boot-anim -gpu swiftshader_indirect \
  > emulator.log 2>&1 &
EMU_PID=$!
cleanup() {
  [ -n "${HOST_PID:-}" ] && kill "$HOST_PID" 2>/dev/null || true
  adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
  kill "$EMU_PID" 2>/dev/null || true
}
trap cleanup EXIT

$ADB wait-for-device
for _ in $(seq 1 300); do
  [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
$ADB shell getprop sys.boot_completed | grep -q 1

$ADB reverse tcp:18787 tcp:18787
export E2E_PORT=18787
export E2E_PUBLIC_RELAY_URL=http://127.0.0.1:18787

cd "$GITHUB_WORKSPACE/host"
./node_modules/.bin/tsx scripts/e2e-host.ts > e2e-host.log 2>&1 &
HOST_PID=$!

for _ in $(seq 1 90); do
  grep -q 'PAIR_LINK=' e2e-host.log && break
  sleep 1
done
LINK=$(grep -o 'PAIR_LINK=.*' e2e-host.log | head -1 | cut -d= -f2-)
test -n "$LINK"
LINK_B64=$(printf %s "$LINK" | base64 | tr -d '\n')

$ADB install -r "$GITHUB_WORKSPACE/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
$ADB shell am force-stop com.kuikagentbus
$ADB shell pm clear com.kuikagentbus
$ADB shell pm grant com.kuikagentbus android.permission.POST_NOTIFICATIONS || true
$ADB logcat -c
$ADB shell am start -n com.kuikagentbus/.e2e.E2eActivity --es e2e_pair_b64 "$LINK_B64"

for _ in $(seq 1 180); do
  LINE=$($ADB logcat -d -s E2eDriver:I | grep 'E2E_RESULT' | tail -1 || true)
  test -n "$LINE" && break
  sleep 1
done
$ADB logcat -d -s E2eDriver:I
echo "--- host log ---"
tail -80 e2e-host.log
$ADB logcat -d -s E2eDriver:I | grep -q 'E2E_RESULT PASS'
