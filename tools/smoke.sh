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
