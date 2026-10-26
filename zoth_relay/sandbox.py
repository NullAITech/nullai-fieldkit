"""Path sandbox: resolve paths (following symlinks) and require them inside allowed dirs."""
from __future__ import annotations

import os
from pathlib import Path


class SandboxError(PermissionError):
    pass


def resolve_inside(path: str, allowed: list[Path], base: Path | None = None) -> Path:
    """Return the fully-resolved path if it lies inside one of `allowed`, else raise.

    Relative paths are resolved against `base` (default: first allowed dir).
    Path.resolve() follows symlinks, so a symlink pointing outside is rejected.
    """
    if not path or "\x00" in path:
        raise SandboxError("empty or invalid path")
    p = Path(os.path.expanduser(path))
    if not p.is_absolute():
        p = (base or allowed[0]) / p
    real = p.resolve(strict=False)
    for root in allowed:
        if real == root or real.is_relative_to(root):
            return real
    raise SandboxError(f"path outside allowed directories: {path}")
