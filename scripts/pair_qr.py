#!/usr/bin/env python3
"""Print a FieldKit pairing QR for the phone app.

Reads the relay-runtime config + token file, builds the pairing payload
{"relay", "token", "name"}, prints a terminal QR and saves pairing.png.

Run it in YOUR terminal (it displays the token as a QR):
  python3 scripts/pair_qr.py [--runtime ../../relay-runtime]
"""
import argparse
import json
import sys
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runtime", default=None,
                    help="relay-runtime dir (default: ../../relay-runtime from this repo)")
    a = ap.parse_args()
    try:
        import qrcode
        import yaml
    except ImportError:
        print("Missing deps. Install with: pip install 'qrcode[pil]' pyyaml")
        return 2
    runtime = Path(a.runtime) if a.runtime else Path(__file__).resolve().parents[2] / "relay-runtime"
    cfg = yaml.safe_load((runtime / "config.yaml").read_text("utf-8")) or {}
    hosts = cfg.get("hosts") or ["127.0.0.1"]
    host = next((h for h in hosts if h not in ("127.0.0.1", "localhost", "::1")), hosts[0])
    port = int(cfg.get("port", 8765))
    tokens = sorted(runtime.glob("*.token"))
    if not tokens:
        print(f"No *.token file in {runtime}. Create one: zoth-relay agent add pixel --profile mobile --token-file {runtime}/pixel.token")
        return 2
    token = tokens[0].read_text("utf-8").strip()
    payload = {"relay": f"http://{host}:{port}", "token": token, "name": "FieldKit Relay"}
    qr = qrcode.QRCode(border=1)
    qr.add_data(json.dumps(payload))
    qr.make(fit=True)
    qr.print_ascii(invert=True)
    out = runtime / "pairing.png"
    qr.make_image().save(out)
    print(f"\nSaved {out}. In the FieldKit app: Scan QR to connect.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
