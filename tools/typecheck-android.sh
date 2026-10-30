#!/usr/bin/env bash
# typecheck-android.sh — fast, heat-light Android source typecheck.
#
# WHY: a full `./gradlew assembleDebug` spins the CPU hard for minutes. On a hot
# box (this project has seen a 100 C package temp) that is expensive and risky.
# This script runs plain `javac` over the whole Java tree instead: seconds of
# low-duty CPU, and it catches the class of error that actually breaks the
# Android source after an edit (bad imports, wrong signatures, typo'd ids).
#
# WHAT IT IS NOT: it is not `assembleDebug`. There is no AAPT2, no dexing, no
# manifest merge, no resource packaging. It proves the Java/Kotlin *source*
# type-checks; it does not prove an installable APK. Always finish with a real
# gradle build before shipping.
#
# It reuses artifacts from the last successful gradle build for R and for
# compiled Kotlin (DatGlasses.kt), because javac cannot compile Kotlin.
#
# Usage:  tools/typecheck-android.sh [--no-thermal-guard]

set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GUARD=1
[ "${1:-}" = "--no-thermal-guard" ] && GUARD=0
if [ "$GUARD" = 1 ]; then
  # Read the CPU package sensor specifically: thermal_zone0 is acpitz on this
  # box (reads ~25 C while the package is at 80 C). Locate x86_pkg_temp by type.
  T=""
  for z in /sys/class/thermal/thermal_zone*; do
    [ -r "$z/temp" ] || continue
    case "$(cat "$z/type" 2>/dev/null)" in
      x86_pkg_temp|coretemp|k10temp) T=$(awk '{printf "%d", $1/1000}' "$z/temp");;
    esac
  done
  if [ -n "$T" ]; then
    echo "package temp: ${T} C"
    if [ "$T" -ge 82 ]; then echo "ABORT: box at ${T} C (>=82) — refusing to add load"; exit 3; fi
  else
    echo "package temp: unavailable (no x86_pkg_temp zone) — continuing"
  fi
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
AJ=$(ls "$SDK"/platforms/android-3[456]/android.jar 2>/dev/null | sort -V | tail -1)
[ -n "$AJ" ] || { echo "ABORT: no android.jar under $SDK/platforms"; exit 2; }

RTXT="android/app/build/intermediates/runtime_symbol_list/debug/processDebugResources/R.txt"
KC="android/app/build/tmp/kotlin-classes/debug"
[ -f "$RTXT" ] || { echo "ABORT: no R.txt ($RTXT) — run one gradle build first"; exit 2; }
[ -d "$KC" ]   || { echo "ABORT: no kotlin classes ($KC) — run one gradle build first"; exit 2; }

OUT="${TMPDIR:-/tmp}/fieldkit-typecheck"
GEN="$OUT/gen/tech/nullai/fieldkit"
rm -rf "$OUT"; mkdir -p "$GEN"

# Rebuild the R class from the last resource pass, plus any @+id added to res/
# since (gradle would regenerate R on the next build; javac cannot).
python3 - "$RTXT" "$GEN/R.java" <<'PY'
import re, subprocess, sys
rtxt, dest = sys.argv[1], sys.argv[2]
types = {}
for line in open(rtxt):
    line = line.strip()
    m = re.match(r'int\[\] (\w+) (\w+) \{(.*)\}', line)
    if m:
        types.setdefault(m.group(1), []).append((m.group(2), [v.strip() for v in m.group(3).split(",") if v.strip()])); continue
    m = re.match(r'int (\w+) (\w+) (0x[0-9a-fA-F]+)', line)
    if m: types.setdefault(m.group(1), []).append((m.group(2), m.group(3)))
known = {n for v in types.values() for n, _ in v}
try:
    ids = subprocess.run(["grep", "-rho", "@+id/[A-Za-z0-9_]*", "android/app/src/main/res"],
                         capture_output=True, text=True).stdout
except OSError:
    ids = ""
v = 0x7f050000
for name in sorted({i.split("/", 1)[1] for i in ids.split() if i}):
    if name not in known:
        v += 1
        types.setdefault("id", []).append((name, hex(v)))
        print(f"  R.id.{name}: not in the last resource pass — synthesised (res/ has it)")
out = ["package tech.nullai.fieldkit;", "", "public final class R {"]
for t in sorted(types):
    out.append(f"  public static final class {t} {{")
    for name, val in types[t]:
        out.append(f"    public static final int[] {name} = {{{', '.join(val)}}};" if isinstance(val, list)
                   else f"    public static final int {name} = {val};")
    out.append("  }")
out.append("}")
open(dest, "w").write("\n".join(out) + "\n")
print(f"  R rebuilt: {sum(len(v) for v in types.values())} fields")
PY

JARS=$(find "$HOME/.gradle/caches" -name '*.jar' ! -name '*-sources.jar' \
        \( -path '*transforms*' -o -path '*modules-2*' \) 2>/dev/null | tr '\n' ':')
find android/app/src/main/java -name '*.java' > "$OUT/sources.txt"
echo "$GEN/R.java" >> "$OUT/sources.txt"

echo "typechecking $(wc -l < "$OUT/sources.txt") files (classpath: $(echo "$JARS" | tr ':' '\n' | grep -c . ) jars, output: $OUT)..."
# An inline -cp overflows the exec arg limit once the whole cache is on the
# classpath, so pass everything through a javac argfile instead.
{
  echo "-nowarn"
  echo "-d"
  echo "$OUT/classes"
  echo "-cp"
  echo "$AJ:$JARS:$KC"
  cat "$OUT/sources.txt"
} > "$OUT/args.txt"
nice -n 19 javac -J-XX:ActiveProcessorCount=2 "@$OUT/args.txt" > "$OUT/javac.log" 2>&1
RC=$?
ERR=$(grep -c 'error:' "$OUT/javac.log" 2>/dev/null || echo 0)
if [ "$RC" = 0 ]; then
  echo "TYPECHECK OK — 0 errors, $(find "$OUT/classes" -name '*.class' | wc -l) classes (NOT an APK: run gradle assembleDebug to ship)"
else
  echo "TYPECHECK FAILED — $ERR errors (log: $OUT/javac.log):"
  grep -E 'error:|Argument list' "$OUT/javac.log" | head -30
fi
exit $RC
