#!/usr/bin/env bash
# zoth-relay installer for ZOTHOS (Debian trixie / KDE) and Parrot OS. Safe to re-run.
#   sudo ./install.sh                     install / upgrade (keeps config + agents)
#   sudo ./install.sh --with-cloudflared  also install cloudflared (.deb from GitHub)
#   sudo ./install.sh --port 8766         use another port (e.g. if parrot-relay uses 8765)
#   sudo ./install.sh --no-desktop        skip the KDE/desktop launcher
set -euo pipefail

SVC_USER=zoth-relay
PREFIX=/opt/zoth-relay
CONF_DIR=/etc/zoth-relay
CONF=$CONF_DIR/config.yaml
AGENTS=$CONF_DIR/agents.yaml
LOG_DIR=/var/log/zoth-relay
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WITH_CF=0; DESKTOP=1; PORT_OPT=""; DETECT_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-cloudflared) WITH_CF=1 ;;
    --no-desktop) DESKTOP=0 ;;
    --detect-only) DETECT_ONLY=1 ;;
    --port) PORT_OPT="$2"; shift ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

log() { printf '\e[1;33m[zoth-relay]\e[0m %s\n' "$*"; }

# 0. distro detection ---------------------------------------------------------
. "${OS_RELEASE:-/etc/os-release}" 2>/dev/null || { echo "no /etc/os-release"; exit 1; }
ids=" ${ID:-} ${ID_LIKE:-} "
# A caller that points OS_RELEASE at a specific file (tests, image builds) means "this IS the
# distro". The host probes below would otherwise report THIS machine for someone else's fixture,
# so they are only consulted when the caller left OS_RELEASE alone.
host=0
[[ -e /etc/zothos-release || -d /usr/share/zothos ]] && host=1
[[ -n ${OS_RELEASE:-} ]] && host=0
if [[ ${ID:-} == parrot || $ids == *" parrot "* ]]; then DISTRO=parrot
elif [[ ${ID:-} == zothos || ${NAME:-} == *ZOTH* || $host -eq 1 ]]; then DISTRO=zothos
elif [[ $ids == *" debian "* || $ids == *" ubuntu "* ]]; then DISTRO=debian
else echo "unsupported distro '${ID:-?}' (need Debian-based: ZOTHOS, Parrot, Debian)"; exit 1; fi
log "detected: $DISTRO (${PRETTY_NAME:-$ID})"
[[ $DETECT_ONLY -eq 1 ]] && { echo "$DISTRO"; exit 0; }
[[ $EUID -eq 0 ]] || { echo "run with sudo (the installer needs root; the service itself never runs as root)"; exit 1; }
command -v systemctl >/dev/null || { echo "systemd required"; exit 1; }

# 1. dependencies -------------------------------------------------------------
log "installing apt dependencies"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 python3-venv python3-pip procps curl ca-certificates >/dev/null
PYV=$(python3 -c 'import sys;print("%d%02d"%sys.version_info[:2])')
[[ $PYV -ge 310 ]] || { echo "python >= 3.10 required"; exit 1; }

if [[ $WITH_CF -eq 1 ]] && ! command -v cloudflared >/dev/null; then
  arch=$(dpkg --print-architecture)
  log "installing cloudflared ($arch) from GitHub releases"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/cf.deb" "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$arch.deb"
  apt-get install -y -qq "$tmp/cf.deb" >/dev/null
  rm -rf "$tmp"
fi

# 2. limited user -------------------------------------------------------------
if ! id "$SVC_USER" >/dev/null 2>&1; then
  log "creating system user $SVC_USER (no login shell, no sudo)"
  useradd --system --create-home --home-dir "/home/$SVC_USER" --shell /usr/sbin/nologin "$SVC_USER"
fi
HOME_DIR=$(getent passwd "$SVC_USER" | cut -d: -f6)
for g in sudo adm docker lxd disk wheel libvirt; do gpasswd -d "$SVC_USER" "$g" >/dev/null 2>&1 || true; done
install -d -m 0750 -o "$SVC_USER" -g "$SVC_USER" "$HOME_DIR" "$HOME_DIR/agent-workspace" "$LOG_DIR"

# 3. code + venv --------------------------------------------------------------
log "installing code to $PREFIX"
install -d -m 0755 "$PREFIX"
rm -rf "$PREFIX/zoth_relay"
cp -r "$SRC_DIR/zoth_relay" "$SRC_DIR/requirements.txt" "$PREFIX/"
find "$PREFIX/zoth_relay" -name '__pycache__' -prune -exec rm -rf {} +
[[ -x $PREFIX/venv/bin/python ]] || python3 -m venv "$PREFIX/venv"
"$PREFIX/venv/bin/pip" install -q --upgrade pip
"$PREFIX/venv/bin/pip" install -q -r "$PREFIX/requirements.txt"
SITE=$("$PREFIX/venv/bin/python" -c 'import sysconfig;print(sysconfig.get_paths()["purelib"])')
echo "$PREFIX" > "$SITE/zoth_relay.pth"
chown -R root:root "$PREFIX"; chmod -R go-w "$PREFIX"   # service user cannot modify its own code
cat > /usr/local/bin/zoth-relay <<WRAP
#!/bin/sh
export ZOTH_RELAY_CONFIG=\${ZOTH_RELAY_CONFIG:-$CONF}
exec $PREFIX/venv/bin/python -m zoth_relay "\$@"
WRAP
chmod 0755 /usr/local/bin/zoth-relay

# 4. config + agents ----------------------------------------------------------
install -d -m 0750 -o root -g "$SVC_USER" "$CONF_DIR"
if [[ ! -f $CONF ]]; then
  log "writing $CONF"
  cp "$SRC_DIR/config.yaml.example" "$CONF"
fi
[[ -n $PORT_OPT ]] && sed -i "s|^port:.*|port: $PORT_OPT|" "$CONF"
PORT=$(awk '/^port:/{print $2}' "$CONF")
if systemctl is-active --quiet parrot-relay 2>/dev/null && [[ $PORT == 8765 ]] && grep -q '^port: 8765' /etc/parrot-relay/config.yaml 2>/dev/null; then
  echo "parrot-relay is running on 8765. Re-run with --port 8766 or uninstall parrot-relay first."; exit 1
fi
chown root:"$SVC_USER" "$CONF"; chmod 0640 "$CONF"
FIRST_TOKEN_MSG=""
if [[ ! -f $AGENTS ]]; then
  printf 'agents: {}\n' > "$AGENTS"; chown root:"$SVC_USER" "$AGENTS"; chmod 0640 "$AGENTS"
  FIRST_TOKEN_MSG=$(ZOTH_RELAY_CONFIG=$CONF "$PREFIX/venv/bin/python" -m zoth_relay agent add local-admin --profile read-only --dashboard)
fi
chown root:"$SVC_USER" "$AGENTS"; chmod 0640 "$AGENTS"

# 5. systemd ------------------------------------------------------------------
log "installing systemd unit"
sed -e "s|@HOME@|$HOME_DIR|g" -e "s|@PREFIX@|$PREFIX|g" "$SRC_DIR/systemd/zoth-relay.service" \
  > /etc/systemd/system/zoth-relay.service
systemctl daemon-reload
systemctl enable zoth-relay >/dev/null 2>&1
systemctl restart zoth-relay
sleep 2
if systemctl is-active --quiet zoth-relay; then log "service running on 127.0.0.1:$PORT"
else log "service failed to start — see: journalctl -u zoth-relay -n 50"; fi

# 6. desktop launcher (KDE / any XDG desktop) ---------------------------------
if [[ $DESKTOP -eq 1 && -d /usr/share/applications ]]; then
  install -D -m 0644 "$SRC_DIR/desktop/zoth-relay.svg" /usr/share/icons/hicolor/scalable/apps/zoth-relay.svg
  sed "s|@PORT@|$PORT|g" "$SRC_DIR/desktop/zoth-relay.desktop" > /usr/share/applications/zoth-relay.desktop
  chmod 0644 /usr/share/applications/zoth-relay.desktop
  command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q /usr/share/icons/hicolor 2>/dev/null || true
  command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications 2>/dev/null || true
  log "desktop launcher installed (opens http://127.0.0.1:$PORT/dashboard)"
fi

# 7. next steps ---------------------------------------------------------------
echo
[[ -n $FIRST_TOKEN_MSG ]] && { echo "  Dashboard agent created:"; echo "$FIRST_TOKEN_MSG" | sed 's/^/    /'; }
cat <<NEXT

  Next steps:
    1. Add an agent per client (token printed once):
         sudo zoth-relay agent add muse --profile exec
         sudo zoth-relay agent add chatgpt --profile workspace
    2. Public URL (optional):  cloudflared tunnel --url http://localhost:$PORT
    3. Connect clients: see docs/ (grok-bot, chatgpt-gpt-actions, claude-cursor-mcp, muse-code, generic-agent)
    Dashboard: http://127.0.0.1:$PORT/dashboard     Audit: sudo tail -f $LOG_DIR/audit.log
NEXT
