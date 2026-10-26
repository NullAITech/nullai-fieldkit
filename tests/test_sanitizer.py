"""Unit tests for the command blocklist and path sandbox. Run: python -m pytest tests -q"""
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from zoth_relay.sandbox import SandboxError, resolve_inside  # noqa: E402
from zoth_relay.sanitizer import BlockedCommand, check_command  # noqa: E402

BLOCKED = [
    "rm -rf /", "rm -rf /*", "rm  -fr   /", "rm -r -f /", 'r"m" -rf /', "rm -rf ~", "rm -rf $HOME",
    "rm --recursive --force /", "rm -rf --no-preserve-root /", "/bin/rm -rf /etc",
    "echo hi; rm -rf /", "true && rm -rf /usr", "$(rm -rf /)", "`rm -rf /`",
    "FOO=1 rm -rf /", "sudo rm -rf /", "env -i rm -rf /", "nohup rm -rf / &", "timeout 5 rm -rf /",
    "rm${IFS}-rf${IFS}/", "mkfs.ext4 /dev/sda1", "/sbin/mkfs -t ext4 /dev/sda", "MKFS.vfat /dev/sdb",
    "wipefs -a /dev/sda", "dd if=/dev/zero of=/dev/sda bs=1M", "dd if=x of=/dev/nvme0n1",
    "shutdown -h now", "reboot", "poweroff", "halt", "systemctl reboot", "init 0",
    ":(){ :|:& };:", ":(){:|:&};:", "bomb(){ bomb|bomb& };bomb",
    "cat x > /dev/sda", "echo cm0gLXJmIC8= | base64 -d | bash", "sudo -i", "su root",
    "busybox rm -rf /", "find / -delete; shred /dev/sda",
]
ALLOWED = [
    "echo ok", "ls -la", "rm -rf ./build", "rm -rf build/ dist/", "rm file.txt",
    "dd if=/dev/zero of=./img bs=1M count=1", "dd if=x of=/dev/null", "python3 -c 'print(1)'",
    "git status && git log -1", "grep -r reboot docs/", "echo shutdown-notes > notes.txt",
    "mkdir -p a/b && cd a && pwd",
]


@pytest.mark.parametrize("cmd", BLOCKED)
def test_blocked(cmd):
    with pytest.raises(BlockedCommand):
        check_command(cmd, ["chmod -R 777 /", "/etc/shadow"])


@pytest.mark.parametrize("cmd", ALLOWED)
def test_allowed(cmd):
    check_command(cmd, ["chmod -R 777 /", "/etc/shadow"])


def test_config_blocklist():
    with pytest.raises(BlockedCommand):
        check_command("cat '/etc/shadow'", ["/etc/shadow"])


def test_sandbox(tmp_path):
    ws = (tmp_path / "ws").resolve()
    ws.mkdir()
    (tmp_path / "secret").write_text("x")
    allowed = [ws]
    assert resolve_inside("a/b.txt", allowed) == ws / "a/b.txt"
    assert resolve_inside(str(ws / "x"), allowed) == ws / "x"
    for bad in ["../secret", str(tmp_path / "secret"), "/etc/passwd", "a/../../secret", "", "a\x00b"]:
        with pytest.raises(SandboxError):
            resolve_inside(bad, allowed)
    # symlink escape
    os.symlink(tmp_path / "secret", ws / "link")
    with pytest.raises(SandboxError):
        resolve_inside("link", allowed)
    os.symlink(tmp_path, ws / "dirlink")
    with pytest.raises(SandboxError):
        resolve_inside("dirlink/secret", allowed)
    # prefix trick: /ws-evil must not match /ws
    (tmp_path / "ws-evil").mkdir()
    with pytest.raises(SandboxError):
        resolve_inside(str(tmp_path / "ws-evil" / "f"), allowed)
