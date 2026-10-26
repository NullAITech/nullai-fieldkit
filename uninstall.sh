#!/usr/bin/env bash
# Remove zoth-relay. Keeps workspace, audit log and /etc/zoth-relay unless --purge.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo"; exit 1; }
PURGE=0; [[ ${1:-} == --purge ]] && PURGE=1
systemctl disable --now zoth-relay 2>/dev/null || true
rm -f /etc/systemd/system/zoth-relay.service /usr/local/bin/zoth-relay \
      /usr/share/applications/zoth-relay.desktop /usr/share/icons/hicolor/scalable/apps/zoth-relay.svg
systemctl daemon-reload
rm -rf /opt/zoth-relay
if [[ $PURGE -eq 1 ]]; then
  rm -rf /var/log/zoth-relay /etc/zoth-relay
  userdel -r zoth-relay 2>/dev/null || true
  echo "purged (user, home/workspace, logs, config + agents removed)"
else
  echo "removed service, code, launcher. Kept: /etc/zoth-relay (agents), /home/zoth-relay, /var/log/zoth-relay"
  echo "run 'sudo ./uninstall.sh --purge' to remove those too"
fi
