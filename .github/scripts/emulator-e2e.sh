#!/usr/bin/env bash
set -euo pipefail

export E2E_PORT=18787
export E2E_PUBLIC_RELAY_URL=http://127.0.0.1:18787
adb reverse tcp:18787 tcp:18787
cd "$GITHUB_WORKSPACE/host"
./node_modules/.bin/tsx scripts/e2e-host.ts > e2e-host.log 2>&1 &
HOST_PID=$!
trap 'kill $HOST_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 90); do
  grep -q 'PAIR_LINK=' e2e-host.log && break
  sleep 1
done
LINK=$(grep -o 'PAIR_LINK=.*' e2e-host.log | head -1 | cut -d= -f2-)
test -n "$LINK"
LINK_B64=$(printf %s "$LINK" | base64 -w0)

adb install -r "$GITHUB_WORKSPACE/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
adb shell pm clear com.kuikagentbus
adb shell pm grant com.kuikagentbus android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n com.kuikagentbus/.e2e.E2eActivity --es e2e_pair_b64 "$LINK_B64"

for _ in $(seq 1 180); do
  LINE=$(adb logcat -d -s E2eDriver:I | grep 'E2E_RESULT' | tail -1 || true)
  test -n "$LINE" && break
  sleep 1
done
adb logcat -d -s E2eDriver:I
echo "--- host log ---"
tail -80 e2e-host.log
adb logcat -d -s E2eDriver:I | grep -q 'E2E_RESULT PASS'
