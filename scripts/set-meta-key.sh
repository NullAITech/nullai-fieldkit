#!/usr/bin/env bash
# Install META_API_KEY for the relay WITHOUT ever printing or echoing it.
# Usage: scripts/set-meta-key.sh            (prompts, hidden input)
#        scripts/set-meta-key.sh --restart  (install + restart the unit)
set -euo pipefail
DEST="${ZOTH_RELAY_ENV:-$HOME/.config/zoth-relay/meta.env}"
mkdir -p "$(dirname "$DEST")"
chmod 700 "$(dirname "$DEST")"
printf 'META_API_KEY (hidden, not echoed): '
IFS= read -rs KEY
echo
if [ -z "${KEY:-}" ]; then echo "empty key — nothing written" >&2; exit 2; fi
umask 077
printf 'META_API_KEY=%s\n' "$KEY" > "$DEST.tmp"
chmod 600 "$DEST.tmp"
mv -f "$DEST.tmp" "$DEST"
unset KEY
echo "wrote $DEST (chmod 600) — key value never printed"
if [ "${1:-}" = "--restart" ]; then
  systemctl --user restart zoth-relay-fieldkit.service
  echo "restarted zoth-relay-fieldkit.service"
  systemctl --user is-active zoth-relay-fieldkit.service
fi
