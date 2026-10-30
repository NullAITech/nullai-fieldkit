#!/usr/bin/env bash
# Android-free check of the on-device voice/person core.
#
# Compiles only the sources that do not import Android (voice prints, the wake-phrase
# grammar, the person descriptor, the utterance segmenter, the print codec) plus three
# harnesses, and runs them
# them on the JVM. No device, no gradle, no SDK - so it is safe to run while the machine
# is hot, and it proves the matching logic before anything is trusted on the phone.
#
#   tools/voicecore/run.sh
#
# Files that DO import Android (VoiceCapture, LockedListener, OwnerPrintPrefs) are
# excluded on purpose; VoiceCapture and LockedListener need the SDK, OwnerPrintPrefs needs
# Context for the SharedPreferences the service reads the print from. The codec they all
# share (OwnerPrintStore) is pure Java and IS compiled here.
# they need the SDK and belong to tools/typecheck-android.sh and a real gradle build.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../../android/app/src/main/java/tech/nullai/fieldkit"
out="${TMPDIR:-/tmp}/fieldkit-voicecore"

[ -d "$src/voice" ] || { echo "voice sources not found at $src" >&2; exit 2; }

rm -rf "$out"; mkdir -p "$out"

mapfile -t files < <(find "$src/voice" "$src/people" -name '*.java' \
    ! -name 'VoiceCapture.java' ! -name 'LockedListener.java' \
    ! -name 'OwnerPrintPrefs.java' | sort)
[ "${#files[@]}" -gt 0 ] || { echo "no Android-free sources found" >&2; exit 2; }

echo "compiling ${#files[@]} Android-free sources"
# nice/capped threads: this runs on a laptop that may already be hot.
nice -n 19 javac -J-XX:ActiveProcessorCount=2 -d "$out" \
    "${files[@]}" "$here/VoiceCoreTest.java" "$here/SegmenterCheck.java" "$here/OwnerPrintCheck.java"

rc=0
for t in VoiceCoreTest SegmenterCheck OwnerPrintCheck; do
    echo
    echo "=== $t ==="
    if nice -n 19 java -cp "$out" "$t"; then :; else rc=1; fi
done

echo
if [ "$rc" -eq 0 ]; then echo "VOICECORE OK - all harnesses passed"; else echo "VOICECORE FAILED"; fi
exit "$rc"
