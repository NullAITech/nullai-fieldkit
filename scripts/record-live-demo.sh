#!/usr/bin/env bash
# Zoth FieldKit (Spotter) — Live Demo Recorder Companion CLI
# Usage:
#   ./scripts/record-live-demo.sh start   # Start in-app DemoRecorder on connected phone
#   ./scripts/record-live-demo.sh stop    # Stop in-app DemoRecorder & pull MP4 + Cue Sheet to ./demos/
#   ./scripts/record-live-demo.sh pull    # Pull all recorded demo MP4s & Cue Sheets from phone

set -euo pipefail

OUT_DIR="${DEMO_OUT_DIR:-/home/zoth/Desktop/Zoth-Meta-Hackathon/demos}"
APP_DIR="/sdcard/Android/data/tech.nullai.fieldkit/files/Movies/SpotterDemos"
GALLERY_DIR="/sdcard/Movies/SpotterDemos"

mkdir -p "$OUT_DIR"

cmd="${1:-help}"

check_adb() {
  if ! adb get-state >/dev/null 2>&1; then
    echo "Error: No Android device detected over ADB. Plug in your Pixel 10 Pro and enable USB debugging." >&2
    exit 1
  fi
}

pull_demos() {
  echo "Pulling recorded Spotter demo videos & cue sheets to: $OUT_DIR"
  adb pull "$APP_DIR/." "$OUT_DIR/" 2>/dev/null || true
  adb pull "$GALLERY_DIR/." "$OUT_DIR/" 2>/dev/null || true
  echo ""
  echo "Available demo recordings in $OUT_DIR:"
  ls -lh "$OUT_DIR" || true
}

case "$cmd" in
  start)
    check_adb
    echo "Starting in-app Spotter Cinema Demo Recorder (720x1280 @ 24fps MP4 + Cue Sheet)..."
    adb shell am start -n tech.nullai.fieldkit/.MainActivity --ez glasses true --ez record_demo true
    echo "● Recording is LIVE on your phone. Use Spotter / Ray-Ban Meta glasses, or tap '■ MM:SS' on screen (or run '$0 stop') when finished."
    ;;
  stop)
    check_adb
    echo "Stopping in-app Spotter Cinema Demo Recorder..."
    adb shell am start -n tech.nullai.fieldkit/.MainActivity --ez record_demo false
    sleep 2
    pull_demos
    ;;
  pull)
    check_adb
    pull_demos
    ;;
  *)
    echo "Usage: $0 {start|stop|pull}"
    echo ""
    echo "  start  — Launch Spotter in Meta Glasses mode and start the built-in DemoRecorder"
    echo "  stop   — Stop DemoRecorder and pull the MP4 video + Markdown cue sheet into $OUT_DIR"
    echo "  pull   — Pull all recorded demos from the phone into $OUT_DIR"
    echo ""
    echo "Note: You can also tap '● REC Demo' directly inside Spotter (Home or Live Call screen) with no USB cable attached!"
    ;;
esac
