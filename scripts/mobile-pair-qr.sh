#!/usr/bin/env bash
# Regenerate the pairing QR (0600) from the mobile token. Never prints the token.
set -euo pipefail
umask 077
DIR="$HOME/.config/zoth-relay"; TOK="$DIR/pixel-mobile.token"; OUT="$DIR/pixel-pair.png"
PORT="${PORT:-8766}"; IP="${IP:-$(tailscale ip -4 | head -1)}"
command -v qrencode >/dev/null || { echo "install qrencode" >&2; exit 1; }
TOKFILE="$TOK" URL="http://$IP:$PORT" python3 -c '
import json, os, socket
tok = open(os.environ["TOKFILE"]).read().strip()
print(json.dumps({"v": 1, "name": socket.gethostname(), "url": os.environ["URL"], "token": tok,
                  "client": "pixel-mobile"}, separators=(",", ":")), end="")' | qrencode -t PNG -s 8 -m 2 -l M -o "$OUT"
chmod 600 "$OUT"
echo "QR written to $OUT for http://$IP:$PORT"
