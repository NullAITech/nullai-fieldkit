#!/usr/bin/env bash
# device-smoke.sh — answer "does the screen-off voice path work on a real phone?"
#
# Off-device this question has no answer: LockedListener only runs while the screen is
# off with a real microphone, so the unit harnesses (tools/voicecore/run.sh) and the
# typecheck cannot reach it. This script drives the one path that can, in one command.
#
# Order of business:
#   1. exactly one attached device (or --serial)
#   2. refuse a stale APK — if the APK is older than the newest .java under app/src you
#      would be testing yesterday's code, and the run would be worthless
#   3. install, grant RECORD_AUDIO, start MainActivity, then confirm from dumpsys that
#      SpotterService is a foreground service and that its type mask includes the mic
#   4. ask the human to lock the phone and speak for ~6 s, then unlock  (this script
#      never injects key events: you lock it, you talk — the phone stays yours)
#   5. read logcat -s SpotterVoice:V back, print every line, and check each claim
#
# What a PASS means, precisely: the service held the microphone with the screen off,
# segmented one utterance, scored it against the enrolled print, and kept a WAV.
# What is still NOT proven by a PASS: that the kept recording sounds like speech. Only
# step 5's pulled WAV can tell you that, which is why --pull exists.
#
# Exit codes: 0 all checks passed; 1 a check failed; 2 the setup refused to run.
set -uo pipefail

PKG=tech.nullai.fieldkit
ACT="$PKG/.MainActivity"
SVC="$PKG/.SpotterService"
TAG=SpotterVoice
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
SRC="$ROOT/android/app/src/main/java"
SERIAL=""
REBUILD=0
PULL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="${2:-}"; shift 2 ;;
    --apk)    APK="${2:-}"; shift 2 ;;
    --pull)   PULL="${2:-}"; shift 2 ;;
    --rebuild) REBUILD=1; shift ;;
    -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

ok=0; bad=0
pass() { printf 'PASS  %s\n' "$1"; ok=$((ok + 1)); }
fail() { printf 'FAIL  %s\n' "$1"; bad=$((bad + 1)); }
info() { printf '      %s\n' "$1"; }
hdr()  { printf '\n== %s\n' "$1"; }

command -v adb >/dev/null || { echo "adb is not on PATH — install platform-tools first" >&2; exit 2; }

hdr "1. device"
if [ -z "$SERIAL" ]; then
  mapfile -t devs < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ "${#devs[@]}" -eq 0 ]; then
    echo "no device: adb sees no attached phone in the 'device' state" >&2
    adb devices >&2
    echo "unlock the phone, accept the USB-debugging prompt, then re-run" >&2
    exit 2
  fi
  if [ "${#devs[@]}" -gt 1 ]; then
    echo "more than one device attached (${devs[*]}) — pick one with --serial" >&2
    exit 2
  fi
  SERIAL="${devs[0]}"
fi
AND=(adb -s "$SERIAL")
info "using $SERIAL ($("${AND[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r'))"
pass "one device in the 'device' state"

hdr "2. is the APK the current code?"
if [ "$REBUILD" = 1 ]; then
  info "temp now: $(awk '{printf "%.0f C", $1/1000}' /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo '?')"
  info "running: ./gradlew assembleDebug -PmwdatAppId=0   (in android/)"
  ( cd "$ROOT/android" && ./gradlew assembleDebug -PmwdatAppId=0 ) || {
    fail "gradle assembleDebug did not succeed — the APK on disk is unchanged"; exit 1; }
fi
[ -f "$APK" ] || { echo "no APK at $APK — build it (--rebuild) or pass --apk" >&2; exit 2; }
apk_age=$(( $(date +%s) - $(stat -c %Y "$APK") ))
newest=$(find "$SRC" -name '*.java' -printf '%T@ %p\n' | sort -n | tail -1)
newest_name="${newest#* }"; newest_ts="${newest%% *}"; newest_ts="${newest_ts%%.*}"
src_age=$(( $(date +%s) - newest_ts ))
info "APK     $(basename "$APK")  ${apk_age}s old"
info "newest  ${newest_name#"$SRC"/}  ${src_age}s old"
if [ "$src_age" -gt "$apk_age" ]; then
  fail "the APK predates the newest source — this would install old code; re-run with --rebuild"
  exit 2
fi
pass "the APK is not older than any source file (it may still be stale vs. HEAD, so check git status)"

hdr "3. install, grant, start"
"${AND[@]}" install -r "$APK" 2>&1 | tail -3 || { fail "adb install failed"; exit 1; }
"${AND[@]}" shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 \
  && info "RECORD_AUDIO granted by adb (debug build)" \
  || info "could not grant RECORD_AUDIO by adb (release build, or already granted) — grant it by hand or talk will be refused"
"${AND[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 \
  && info "POST_NOTIFICATIONS granted so the service notification is visible"
"${AND[@]}" logcat -c
"${AND[@]}" shell am start -n "$ACT" >/dev/null 2>&1 || fail "could not start $ACT"
sleep 4

dumpsys_out="$("${AND[@]}" shell dumpsys activity services "$SVC" 2>/dev/null)"
if grep -q 'isForeground=true' <<<"$dumpsys_out"; then
  pass "SpotterService is running as a foreground service"
else
  fail "SpotterService is not a running foreground service — the service never started (check logcat below)"
fi
type_line="$(grep -o 'foregroundServiceTypes=[^ ,}]*' <<<"$dumpsys_out" | head -1)"
if [ -n "$type_line" ]; then
  info "dumpsys: $type_line  (mic bit = 0x80 / 128)"
  # 0xd1 = camera|microphone|connectedDevice|dataSync, the mask SpotterService passes.
  case "$type_line" in
    *=0xd1|*=0x00d1|*0x80*|*[mM]icrophone*) pass "the foreground type mask includes the microphone bit" ;;
    *) fail "the foreground type mask does NOT include the microphone bit ($type_line)" ;;
  esac
else
  info "dumpsys did not print a foregroundServiceTypes= field on this OS version — the mic type is unverified here"
fi

hdr "4. the screen-off part (this is what needs you)"
info "the app is in front and holding the microphone, so the service is NOT listening yet"
printf '      lock the phone (power button), say a sentence or two — aloud, for about 6 seconds —\n'
printf '      then unlock it and press Enter here.\n'
read -r _

hdr "5. what the app actually said"
log="$("${AND[@]}" logcat -d -s "$TAG:V" 2>/dev/null | tr -d '\r')"
if [ -z "$log" ]; then
  fail "no $TAG lines at all — the voice path produced no logging (it may never have run)"
else
  printf '%s\n' "$log" | tail -40 | sed 's/^/      /'
fi
grep -q 'screen-off listening started' <<<"$log" \
  && pass "the service reported it was listening with the screen off" \
  || fail 'no "screen-off listening started" line — the listener never came up with the screen off'
grep -q 'not starting: RECORD_AUDIO is not granted' <<<"$log" \
  && fail "the service refused to listen: RECORD_AUDIO is not granted"
grep -q 'NOT started: the microphone is held' <<<"$log" \
  && fail "the microphone was held by someone else when the screen went off (handover ordering)"
grep -q 'no enrolled voice' <<<"$log" \
  && info "no voice was enrolled, so nothing could be refused: enrol first, then re-run for a real gate test"
grep -q 'handing the microphone back' <<<"$log" \
  && pass "the activity got the microphone back on resume (handover ran both ways)"

kept="$(grep -c 'kept .* ms of the wearer' <<<"$log")"
refused="$(grep -c 'failed the print gate' <<<"$log")"
scores="$(grep -o 'score [-0-9.]*' <<<"$log" | awk '{print $2}' | paste -sd, -)"
if [ "$kept" -gt 0 ]; then
  pass "kept $kept utterance(s) as the wearer (scores: ${scores:-none printed})"
elif [ "$refused" -gt 0 ]; then
  pass "the gate ran and refused $refused utterance(s) — that is the gate working, not a failure"
else
  fail "no utterance was segmented: nothing above the noise floor, or the read loop never produced a block"
fi

hdr "6. the recording itself"
wavs="$("${AND[@]}" shell run-as "$PKG" ls -1 cache 2>/dev/null | tr -d '\r' | grep -c '^locked-.*\.wav$')"
if [ "${wavs:-0}" -gt 0 ]; then
  pass "$wavs locked-*.wav file(s) landed in the app cache"
else
  fail "no locked-*.wav in the app cache — the utterance was segmented but never written"
fi
if [ -n "$PULL" ]; then
  mkdir -p "$PULL" || { fail "could not create $PULL"; exit 1; }
  for w in $("${AND[@]}" shell run-as "$PKG" ls -1 cache 2>/dev/null | tr -d '\r' | grep '^locked-.*\.wav$'); do
    "${AND[@]}" exec-out run-as "$PKG" cat "cache/$w" > "$PULL/$w" 2>/dev/null \
      && info "pulled $PULL/$w" || info "could not pull $w"
  done
  info "listen to those: they are the only proof that the kept audio is your voice"
fi

printf '\n%s passed, %s failed\n' "$ok" "$bad"
[ "$bad" -eq 0 ] || exit 1
info "note: this proves the transport, not the model — that the recording sounds like"
info "      speech, and like you, is what step 6's WAV is for."
