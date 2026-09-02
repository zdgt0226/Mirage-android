#!/usr/bin/env bash
set -e
DEVICE="R5CX21FD9PX"

echo "========================================================"
echo "    Mirage-Android Multi-Round Stress Test Engine       "
echo "========================================================"

get_snapshot() {
    adb -s $DEVICE shell 'curl -s http://127.0.0.1:9090/debug/snapshot' 2>/dev/null || echo "{}"
}

echo "--> [Initial Baseline Snapshot]"
get_snapshot

echo ""
echo "=== [Round 1: YouTube 4K Streaming & Media Stream Stress] ==="
adb -s $DEVICE shell am start -n com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity
sleep 3
# Swipe up and down rapidly to load dozens of video thumbnails and previews
for i in {1..8}; do
    adb -s $DEVICE shell input swipe 540 1800 540 400 150
    sleep 0.8
done
adb -s $DEVICE shell screencap -p /sdcard/stress_r1.png
adb -s $DEVICE pull /sdcard/stress_r1.png /root/.gemini/antigravity-cli/brain/0ac75883-6150-4a61-9260-8d2a4ca9cbb1/stress_r1.png
echo "--> Round 1 Snapshot:"
get_snapshot

echo ""
echo "=== [Round 2: Google Play Store Heavy Image & Catalog Burst] ==="
adb -s $DEVICE shell am start -n com.android.vending/com.google.android.finsky.activities.MainActivity
sleep 2
# Switch through all 4 bottom tabs rapidly + scroll
for tab in "180 2250" "400 2250" "650 2250" "900 2250" "400 2250"; do
    adb -s $DEVICE shell input tap $tab
    sleep 1.2
    adb -s $DEVICE shell input swipe 540 1600 540 300 200
    sleep 0.8
done
adb -s $DEVICE shell screencap -p /sdcard/stress_r2.png
adb -s $DEVICE pull /sdcard/stress_r2.png /root/.gemini/antigravity-cli/brain/0ac75883-6150-4a61-9260-8d2a4ca9cbb1/stress_r2.png
echo "--> Round 2 Snapshot:"
get_snapshot

echo ""
echo "=== [Round 3: Google Maps High-Density Vector Tile Burst] ==="
adb -s $DEVICE shell monkey -p com.google.android.apps.maps -c android.intent.category.LAUNCHER 1
sleep 3
# Rapid pan and multi-directional map tiles fetch
for i in {1..6}; do
    adb -s $DEVICE shell input swipe 200 1000 800 1000 200
    sleep 0.6
    adb -s $DEVICE shell input swipe 540 1200 540 600 200
    sleep 0.6
done
adb -s $DEVICE shell screencap -p /sdcard/stress_r3.png
adb -s $DEVICE pull /sdcard/stress_r3.png /root/.gemini/antigravity-cli/brain/0ac75883-6150-4a61-9260-8d2a4ca9cbb1/stress_r3.png
echo "--> Round 3 Snapshot:"
get_snapshot

echo ""
echo "=== [Round 4: Multi-Threaded High Concurrency TCP/TLS Connection Storm] ==="
# Launch 20 parallel curls over TUN to both CN Direct and Overseas Proxy endpoints
adb -s $DEVICE shell '
for i in $(seq 1 10); do
    curl -s -o /dev/null https://www.bilibili.com &
    curl -s -o /dev/null https://www.baidu.com &
    curl -s -o /dev/null https://www.google.com &
    curl -s -o /dev/null https://www.cloudflare.com &
done
wait
echo "All 40 concurrent connection requests completed."
'
echo "--> Round 4 Snapshot:"
get_snapshot

echo ""
echo "=== [Round 5: App Switching & Lifecycle Memory / Socket Stability] ==="
# Rapidly switch between 4 Google apps
for app in "com.google.android.youtube" "com.android.chrome" "com.google.android.gm" "com.android.vending"; do
    adb -s $DEVICE shell monkey -p $app -c android.intent.category.LAUNCHER 1
    sleep 1.5
done
adb -s $DEVICE shell screencap -p /sdcard/stress_r5.png
adb -s $DEVICE pull /sdcard/stress_r5.png /root/.gemini/antigravity-cli/brain/0ac75883-6150-4a61-9260-8d2a4ca9cbb1/stress_r5.png

echo ""
echo "--> [Final Cumulative Snapshot]"
get_snapshot

