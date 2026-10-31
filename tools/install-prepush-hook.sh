#!/usr/bin/env bash
# Make the pre-push secret guard mandatory for this clone.
#
#   tools/install-prepush-hook.sh            install (and prove the guard works)
#   tools/install-prepush-hook.sh --check    report status, change nothing
#   tools/install-prepush-hook.sh --remove   turn the hook off again
#
# Installing sets core.hooksPath to <toplevel>/.githooks, so every `git push`
# from this clone runs .githooks/pre-push -> tools/prepush-secret-scan.sh --hook
# before any object is transferred.  The self-check runs first and the install
# is refused if the guard cannot catch its own planted leaks: a guard that
# fails its own controls is worse than no guard, because people trust it.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCAN="$HERE/prepush-secret-scan.sh"
MODE="install"
for a in "$@"; do
  case "$a" in
    --check)  MODE="check" ;;
    --remove) MODE="remove" ;;
    -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "install-prepush-hook: unknown option $a" >&2; exit 2 ;;
  esac
done

ROOT="$(git -C "$HERE" rev-parse --show-toplevel 2>/dev/null)" || {
  echo "install-prepush-hook: $HERE is not inside a git working tree" >&2; exit 1
}
HOOKDIR="$ROOT/.githooks"
HOOK="$HOOKDIR/pre-push"
CUR="$(git -C "$ROOT" config --local --get core.hooksPath 2>/dev/null || true)"

status() {
  echo "repository:   $ROOT"
  echo "core.hooksPath: ${CUR:-(unset -> git uses .git/hooks)}"
  if [ "$CUR" = "$HOOKDIR" ] && [ -x "$HOOK" ]; then
    echo "state:        ACTIVE - every git push in this clone is scanned first"
  elif [ -f "$HOOK" ]; then
    echo "state:        INSTALLED BUT NOT ACTIVE (run without --check to fix)"
  else
    echo "state:        NOT INSTALLED - pushes from this clone are unguarded"
  fi
}

case "$MODE" in
  check) status; [ "$CUR" = "$HOOKDIR" ] && [ -x "$HOOK" ] && exit 0 || exit 1 ;;
  remove)
    if [ "$CUR" = "$HOOKDIR" ]; then
      git -C "$ROOT" config --local --unset core.hooksPath
      echo "pre-push guard: disabled (core.hooksPath unset; push is unguarded again)"
    else
      echo "pre-push guard: nothing to disable (core.hooksPath was '${CUR:-unset}')"
    fi
    exit 0 ;;
esac

[ -f "$SCAN" ] || { echo "install-prepush-hook: missing $SCAN" >&2; exit 1; }
[ -f "$HOOK" ] || { echo "install-prepush-hook: missing $HOOK - the hook file is versioned; restore it" >&2; exit 1; }

echo "== proving the guard before trusting it: tools/prepush-secret-scan.sh --self-check"
if ! bash "$SCAN" --self-check; then
  echo "install-prepush-hook: REFUSED - the guard failed its own controls; not installing." >&2
  exit 1
fi

chmod +x "$HOOK" "$SCAN" 2>/dev/null || true
if ! git -C "$ROOT" config --local core.hooksPath "$HOOKDIR"; then
  echo "install-prepush-hook: could not set core.hooksPath" >&2; exit 1
fi

# read it back rather than trusting the write
CUR="$(git -C "$ROOT" config --local --get core.hooksPath 2>/dev/null || true)"
if [ "$CUR" != "$HOOKDIR" ]; then
  echo "install-prepush-hook: core.hooksPath reads back as '${CUR:-unset}', expected '$HOOKDIR'" >&2
  exit 1
fi

echo
status
cat <<'NOTE'

What this means in practice:
  * `git push` now runs the scan on the outgoing range; a finding aborts it.
  * `git push --no-verify` still bypasses it - that is git's own escape hatch,
    use it deliberately and say why in the PR/commit thread.
  * Because core.hooksPath is set for this clone only, a fresh clone must run
    tools/install-prepush-hook.sh once. `--check` is the quick way to confirm.
NOTE
