#!/usr/bin/env bash
# Runs MemoryProbeTest on the emulator (started by memory-probe.yml with 2 GB of RAM) and samples
# memory from outside every 5 seconds: MovieHub's own processes (PSS from dumpsys meminfo) and the
# whole device's available memory. Results go to build/reports/memory/ and the job summary.
set -uo pipefail
build=android-tv-launcher/app/build
out="$build/reports/memory"
mkdir -p "$out"
pkg=local.tv.home.experimental

adb logcat -c || true
gradle -p android-tv-launcher --no-daemon -Pexperimental -PmemoryProbe connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=local.tv.home.MemoryProbeTest" \
    > "$build/memory-probe-gradle.log" 2>&1 &
gradle_pid=$!

echo "seconds,phase,process,pss_kb,device_total_kb,device_available_kb" > "$out/samples.csv"
start=$(date +%s)
phase="build"
while kill -0 "$gradle_pid" 2>/dev/null; do
    now=$(( $(date +%s) - start ))
    latest=$(timeout 10s adb logcat -d -s MemoryProbe:I 2>/dev/null | grep -o 'phase=[a-z-]*' | tail -1 | cut -d= -f2)
    [ -n "$latest" ] && phase="$latest"
    mem=$(timeout 10s adb shell cat /proc/meminfo 2>/dev/null)
    total=$(echo "$mem" | awk '/^MemTotal:/{print $2}')
    avail=$(echo "$mem" | awk '/^MemAvailable:/{print $2}')
    # "  123,456K: local.tv.home.experimental (pid 1234 / activities)"; isolated provider
    # processes are listed by their process name, which starts with the package name.
    timeout 20s adb shell dumpsys meminfo 2>/dev/null |
        sed -n '/Total PSS by process/,/Total PSS by OOM/p' |
        grep -F "$pkg" |
        sed -E 's/^ *([0-9,]+)K: ([^ ]+) .*/\1 \2/' |
        while read -r kb name; do
            echo "$now,$phase,$name,${kb//,/},${total:-},${avail:-}" >> "$out/samples.csv"
        done
    echo "$now,$phase,DEVICE,,${total:-},${avail:-}" >> "$out/samples.csv"
    sleep 5
done
wait "$gradle_pid"
result=$?

adb logcat -d -s MemoryProbe:I > "$out/probe.log" 2>/dev/null || true
adb logcat -d -b events 2>/dev/null | grep -E "am_kill|am_proc_died|am_low_memory" | grep -F "$pkg" > "$out/kills.log" || true
adb logcat -d 2>/dev/null | grep -E -i "lowmemorykiller|lmkd" | grep -F "$pkg" >> "$out/kills.log" || true
adb logcat -d -b crash > "$out/crash.log" 2>/dev/null || true
tail -40 "$build/memory-probe-gradle.log" > "$out/gradle-tail.log" || true

python3 android-tv-launcher/tests/summarise-memory-probe.py "$out" "$result"
exit "$result"
