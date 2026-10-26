"""Kernel-enforced filesystem + TCP-bind sandbox for `dev` agents (Linux Landlock).

Used as an exec wrapper:  python -m zoth_relay.landlock --rw DIR [--ports 4100-4199] -- bash -c CMD
The wrapper restricts itself (unprivileged; works under NoNewPrivileges) then execs the
command, so the agent's whole process tree can only:
  * read+write+execute inside its own workspace DIR,
  * read/execute system dirs (/usr /bin /lib /opt /etc minus /etc/zoth-relay, /proc, /sys, /run),
  * TCP-bind only to the reserved dev port range (Landlock ABI >= 4),
  * not signal processes outside its domain (ABI >= 6), e.g. the relay itself.
Everything else (other agents' dirs, the relay's home and audit log, /home/*) is denied
even though the command runs as the same `zoth-relay` user.
"""
from __future__ import annotations

import ctypes
import os
import sys

SYS_CREATE, SYS_ADD, SYS_RESTRICT = 444, 445, 446  # same numbers on x86_64 and aarch64
PR_SET_NO_NEW_PRIVS = 38
FS_EXECUTE, FS_WRITE_FILE, FS_READ_FILE, FS_READ_DIR = 1, 2, 4, 8
FS_ALL_V1 = (1 << 13) - 1
FS_REFER, FS_TRUNCATE, FS_IOCTL_DEV = 1 << 13, 1 << 14, 1 << 15
NET_BIND_TCP = 1
SCOPE_ABSTRACT_UNIX, SCOPE_SIGNAL = 1, 2
READ_ONLY_ROOTS = ["/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32", "/opt",
                   "/proc", "/sys", "/run", "/var/lib/dpkg", "/var/lib/ca-certificates"]
ETC_DENY = {"zoth-relay", "shadow", "gshadow", "shadow-", "gshadow-", "sudoers", "sudoers.d"}

_libc = ctypes.CDLL(None, use_errno=True)
_libc.syscall.restype = ctypes.c_long


class _PathBeneath(ctypes.Structure):
    _pack_ = 1
    _fields_ = [("allowed_access", ctypes.c_uint64), ("parent_fd", ctypes.c_int32)]


class _NetPort(ctypes.Structure):
    _fields_ = [("allowed_access", ctypes.c_uint64), ("port", ctypes.c_uint64)]


class _Attr(ctypes.Structure):
    _fields_ = [("fs", ctypes.c_uint64), ("net", ctypes.c_uint64), ("scoped", ctypes.c_uint64)]


def abi_version() -> int:
    v = _libc.syscall(SYS_CREATE, None, ctypes.c_size_t(0), ctypes.c_uint32(1))
    return int(v) if v > 0 else 0


def parse_ports(spec: str) -> list[int]:
    out: list[int] = []
    for part in (spec or "").split(","):
        part = part.strip()
        if not part:
            continue
        lo, _, hi = part.partition("-")
        lo_i, hi_i = int(lo), int(hi or lo)
        if not (1024 <= lo_i <= hi_i <= 65535):
            raise ValueError(f"bad port range {part!r}")
        out.extend(range(lo_i, hi_i + 1))
    return out


def _add_path(rs: int, path: str, access: int) -> None:
    try:
        fd = os.open(path, os.O_PATH | os.O_CLOEXEC)
    except OSError:
        return
    try:
        if not os.path.isdir(path):  # file rules may only carry file rights
            access &= FS_EXECUTE | FS_WRITE_FILE | FS_READ_FILE | FS_TRUNCATE | FS_IOCTL_DEV
        r = _PathBeneath(access, fd)
        if _libc.syscall(SYS_ADD, rs, 1, ctypes.byref(r), 0) != 0:
            raise OSError(ctypes.get_errno(), f"landlock_add_rule {path}")
    finally:
        os.close(fd)


def restrict(rw_dirs: list[str], ports: list[int]) -> int:
    """Apply the sandbox to the current process. Returns the ABI used. Raises if unsupported."""
    abi = abi_version()
    if abi < 1:
        raise OSError("Landlock not available on this kernel")
    fs_all = FS_ALL_V1 | (FS_REFER if abi >= 2 else 0) | (FS_TRUNCATE if abi >= 3 else 0) | (FS_IOCTL_DEV if abi >= 5 else 0)
    ro = FS_EXECUTE | FS_READ_FILE | FS_READ_DIR
    attr = _Attr(fs_all, NET_BIND_TCP if abi >= 4 else 0, (SCOPE_ABSTRACT_UNIX | SCOPE_SIGNAL) if abi >= 6 else 0)
    size = 24 if abi >= 6 else 16 if abi >= 4 else 8
    rs = _libc.syscall(SYS_CREATE, ctypes.byref(attr), ctypes.c_size_t(size), ctypes.c_uint32(0))
    if rs < 0:
        raise OSError(ctypes.get_errno(), "landlock_create_ruleset")
    try:
        for p in READ_ONLY_ROOTS:
            _add_path(rs, p, ro)
        for name in sorted(os.listdir("/etc")):
            if name not in ETC_DENY:
                _add_path(rs, os.path.join("/etc", name), ro)
        # /dev is a PrivateDevices tmpfs (null, zero, urandom, tty, pts, shm)
        _add_path(rs, "/dev", ro | FS_WRITE_FILE | (FS_TRUNCATE if abi >= 3 else 0) | (FS_IOCTL_DEV if abi >= 5 else 0))
        for d in rw_dirs:
            _add_path(rs, d, fs_all)
        if abi >= 4:
            for port in ports:
                r = _NetPort(NET_BIND_TCP, port)
                if _libc.syscall(SYS_ADD, rs, 2, ctypes.byref(r), 0) != 0:
                    raise OSError(ctypes.get_errno(), f"landlock_add_rule port {port}")
        if _libc.prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), "prctl(NO_NEW_PRIVS)")
        if _libc.syscall(SYS_RESTRICT, rs, 0) != 0:
            raise OSError(ctypes.get_errno(), "landlock_restrict_self")
    finally:
        os.close(rs)
    return abi


def main(argv: list[str]) -> None:
    rw, ports = [], ""
    i = 0
    while i < len(argv) and argv[i] != "--":
        if argv[i] == "--rw":
            rw.append(argv[i + 1]); i += 2
        elif argv[i] == "--ports":
            ports = argv[i + 1]; i += 2
        else:
            sys.exit(f"landlock: unknown arg {argv[i]}")
    cmd = argv[i + 1:]
    if not rw or not cmd:
        sys.exit("usage: python -m zoth_relay.landlock --rw DIR [--ports A-B] -- PROG ARGS...")
    try:
        restrict(rw, parse_ports(ports))
    except (OSError, ValueError) as e:  # fail closed
        sys.stderr.write(f"[zoth-relay] sandbox setup failed, refusing to run: {e}\n")
        sys.exit(126)
    os.execvp(cmd[0], cmd)


if __name__ == "__main__":
    main(sys.argv[1:])
