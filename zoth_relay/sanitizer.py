"""Command sanitizer / blocklist.

This is a defence-in-depth layer, NOT a sandbox. A determined attacker holding the
token can always find shell tricks; the real protections are: non-root user,
systemd hardening (see zoth-relay.service), and keeping the token secret.

Strategy:
  1. Normalise: drop quotes/backslashes, expand ${IFS}/$IFS, collapse whitespace, lowercase.
  2. Split into simple commands on ; & | && || newlines, $( ), backticks, ( ) { }.
  3. For each simple command strip env assignments (FOO=1) and wrapper programs
     (sudo, env, nohup, exec, command, timeout N, nice, busybox, xargs ...).
  4. Check the real program name (basename, so /sbin/mkfs is caught) against banned
     programs and check argument patterns (rm -rf /, dd of=/dev/..., > /dev/sda ...).
  5. Also check configured extra substrings against the normalised command.
"""
from __future__ import annotations

import re

# Programs that are refused outright regardless of arguments.
BANNED_PROGRAMS = {
    "shutdown", "reboot", "poweroff", "halt", "init", "telinit",
    "mkfs", "mke2fs", "mkswap", "wipefs", "fdisk", "sfdisk", "cfdisk", "parted",
    "gdisk", "sgdisk", "shred", "blkdiscard", "cryptsetup", "lvremove", "vgremove",
    "pvremove", "mdadm", "sudo", "su", "doas", "pkexec", "passwd", "chpasswd",
    "useradd", "userdel", "usermod", "visudo", "insmod", "rmmod", "modprobe",
    "kexec", "crontab", "systemctl", "iptables", "nft", "ufw",
}
# Prefix matches (mkfs.ext4, mkfs.vfat, ...).
BANNED_PREFIXES = ("mkfs.", "mkfs-", "fsck.")

# Wrappers that just run another program: skip them to find the real one.
WRAPPERS = {"env", "nohup", "exec", "command", "builtin", "nice", "ionice", "time",
            "stdbuf", "setsid", "busybox", "xargs", "chrt", "taskset", "unbuffer",
            "timeout", "watch", "strace", "ltrace", "eval", "then", "do", "else", "!"}

SPLIT_RE = re.compile(r"\|\||&&|[;&|\n()`{}]|\$\(")
ASSIGN_RE = re.compile(r"^[a-z_][a-z0-9_]*=")
FORKBOMB_RE = re.compile(r":\s*\(\s*\)\s*\{.*:\s*\|\s*:.*&.*\}")
GENERIC_FORKBOMB_RE = re.compile(r"(\w+)\s*\(\s*\)\s*\{[^}]*\b\1\s*\|\s*\1")
DEV_WRITE_RE = re.compile(r">\s*/dev/(sd|nvme|hd|vd|mmcblk|xvd|dm-|md|loop|mapper|disk)")
PIPE_TO_SHELL_RE = re.compile(r"(base64\s+(-d|--decode)|xxd\s+-r).*\|\s*(ba|z|da|k)?sh\b")
ROOTISH_TARGETS = {"/", "/*", "~", "~/", "$home", "${home}", "/home", "/etc", "/usr",
                   "/var", "/boot", "/bin", "/sbin", "/lib", "/opt", "/root", ".", "..", "*"}


class BlockedCommand(ValueError):
    pass


def normalise(cmd: str) -> str:
    s = cmd.replace("\\\n", " ")
    s = re.sub(r"\$\{?ifs\}?", " ", s, flags=re.I)
    s = s.replace("'", "").replace('"', "").replace("\\", "")
    s = re.sub(r"[ \t\r\f\v]+", " ", s)
    return s.strip().lower()


def _real_program(words: list[str]) -> tuple[str, list[str]]:
    i = 0
    while i < len(words):
        w = words[i]
        if ASSIGN_RE.match(w):
            i += 1
            continue
        base = w.rsplit("/", 1)[-1]
        if base in WRAPPERS:
            i += 1
            # skip wrapper options/numeric args (timeout 5, nice -n 10, env -i)
            while i < len(words) and (words[i].startswith("-") or re.fullmatch(r"[\d.]+[smhd]?", words[i])):
                i += 1
            continue
        return base, words[i + 1:]
    return "", []


def _check_simple(words: list[str]) -> str | None:
    prog, args = _real_program(words)
    if not prog:
        return None
    if prog in BANNED_PROGRAMS or prog.startswith(BANNED_PREFIXES):
        return f"program '{prog}' is blocked"
    if prog == "rm":
        flags = "".join(a.lstrip("-") for a in args if a.startswith("-") and not a.startswith("--"))
        long = {a for a in args if a.startswith("--")}
        recursive = "r" in flags or "--recursive" in long
        targets = [a for a in args if not a.startswith("-")]
        if "--no-preserve-root" in long:
            return "rm --no-preserve-root is blocked"
        if recursive and any(t.rstrip("/") in {x.rstrip("/") for x in ROOTISH_TARGETS} or t in ROOTISH_TARGETS
                             or re.fullmatch(r"/[^/]*/?\*?", t) for t in targets):
            return "recursive rm on a root/system/home path is blocked"
    if prog == "dd" and any(a.startswith("of=/dev/") and not a.startswith(("of=/dev/null", "of=/dev/stdout", "of=/dev/stderr")) for a in args):
        return "dd to a device is blocked"
    if prog in {"chmod", "chown", "chgrp"} and any(a in {"-r", "--recursive"} for a in args) and any(a in ROOTISH_TARGETS for a in args):
        return f"recursive {prog} on a system path is blocked"
    if prog == "mv" and any(a in {"/", "/*"} for a in args):
        return "mv of / is blocked"
    return None


def check_command(cmd: str, extra_blocklist: list[str] | None = None) -> None:
    """Raise BlockedCommand if `cmd` looks destructive."""
    if not cmd or not cmd.strip():
        raise BlockedCommand("empty command")
    if "\x00" in cmd:
        raise BlockedCommand("NUL byte in command")
    norm = normalise(cmd)
    compact = re.sub(r"\s+", "", norm)

    if FORKBOMB_RE.search(norm) or ":(){:|:&};:" in compact or GENERIC_FORKBOMB_RE.search(norm):
        raise BlockedCommand("fork bomb pattern")
    if DEV_WRITE_RE.search(norm):
        raise BlockedCommand("redirect to a block device")
    if PIPE_TO_SHELL_RE.search(norm):
        raise BlockedCommand("decoded payload piped to a shell")

    for entry in extra_blocklist or []:
        e = normalise(entry)
        if e and (e in norm or re.sub(r"\s+", "", e) in compact):
            raise BlockedCommand(f"matches blocklist entry '{entry}'")

    for segment in SPLIT_RE.split(norm):
        words = segment.split()
        # strip leading redirections like "2>&1" fragments harmlessly
        reason = _check_simple(words)
        if reason:
            raise BlockedCommand(reason)
