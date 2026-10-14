#!/bin/bash
# Wait until a booted (or snapshot-restored) CI emulator has finished its
# start-up work, before anything drives the UI.
#
# Right after boot, and again after a snapshot restore moves the clock forward,
# Google Play services handles a burst of broadcasts and apps are compiled in the
# background. On a CI emulator that starves System UI until Android shows
# "System UI isn't responding", which takes window focus from the app under test.
#
# Usage: wait_for_emulator_idle.sh [--compile]
#   --compile  also finish the pending background compilation (used before the
#              boot snapshot is saved, so restored runs never repeat it)
set -euo pipefail

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done

if [ "${1:-}" = "--compile" ]; then
  echo "finishing background app compilation..."
  adb shell cmd package bg-dexopt-job > /dev/null
fi

echo "waiting for pending broadcasts..."
if ! adb shell 'timeout 300 am wait-for-broadcast-idle' > /dev/null; then
  echo "warning: broadcasts were still pending after 5 minutes" >&2
fi

# Then wait for the CPU to calm down: under 50% busy across two 5-second samples.
# (Android's load average stays high even when idle, so CPU time is measured.)
cpu_busy_percent() {
  adb shell 'head -1 /proc/stat; sleep 5; head -1 /proc/stat' | tr -d '\r' | awk '
    { idle = $5 + $6; total = 0; for (i = 2; i <= NF; i++) total += $i }
    NR == 1 { idle0 = idle; total0 = total }
    NR == 2 { printf "%d", 100 * (1 - (idle - idle0) / (total - total0)) }'
}
deadline=$((SECONDS + 300))
calm=0
while (( calm < 2 )); do
  busy=$(cpu_busy_percent)
  if (( busy < 50 )); then calm=$((calm + 1)); else calm=0; fi
  if (( SECONDS >= deadline )); then
    echo "warning: CPU still ${busy}% busy after 5 minutes" >&2
    break
  fi
done
echo "CPU ${busy}% busy"
