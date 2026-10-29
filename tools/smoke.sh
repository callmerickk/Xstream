#!/bin/bash
# Installs the APK on the emulator, opens Xstream, and reports any crash as a GitHub annotation.
adb install -r app/build/outputs/apk/phone/debug/*.apk
adb logcat -c
adb shell am start -n com.example.xstream/com.example.streamnest.MainActivity
sleep 30
adb logcat -d -b crash > crash.txt || true
adb logcat -d | grep -E "AndroidRuntime|FATAL|Caused by|Exception|^.*\tat " | head -150 > logcat.txt || true
adb shell pidof com.example.xstream > pid.txt || true
{ echo "running pid: $(cat pid.txt)"; cat crash.txt | head -70; echo "----"; grep -A40 "FATAL" logcat.txt | head -60; } > report.txt
cat report.txt
msg=$(head -70 report.txt | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}')
echo "::warning title=Smoke::$msg"

# Live server (64-bit app): it starts by itself after the app opens. Ask it for its version, a movie's streams and the
# latest songs through a forwarded port (the runner has internet, so this is a real end-to-end check).
{
  P=$(adb shell pm path com.example.xstream | head -1 | sed 's/package://; s/base.apk//' | tr -d '\r')
  echo "lib dir: ${P}lib"; adb shell ls -la "${P}lib/arm64" 2>&1 | grep -i xlive
  adb forward tcp:18088 tcp:8088
  ok=""
  for i in $(seq 1 20); do v=$(curl -s -m 5 http://127.0.0.1:18088/api/version); if [ -n "$v" ]; then ok=1; echo "version: $v"; break; fi; sleep 3; done
  if [ -z "$ok" ]; then
    echo "The app's Live server did not answer; running the binary by hand:"
    adb shell "mkdir -p /data/local/tmp/pm && cd /data/local/tmp/pm && PM_APP_ROOT=/data/local/tmp/pm HOME=/data/local/tmp/pm timeout 10 ${P}lib/arm64/libxlive.so --port 8099 --host 127.0.0.1 --no-open 2>&1 | head -40"
  else
    echo "--- stream (first ask)"; curl -s -m 40 http://127.0.0.1:18088/stream/movie/tt0111161.json | head -c 700; echo
    sleep 25
    echo "--- stream (after 25 s)"; curl -s -m 40 http://127.0.0.1:18088/stream/movie/tt1375666.json | head -c 900; echo
    echo "--- music latest"; curl -s -m 40 http://127.0.0.1:18088/music/catalog/latest.json | head -c 600; echo
  fi
} > live.txt 2>&1
cat live.txt
msg=$(head -60 live.txt | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}')
echo "::warning title=Live server::$msg"
