"""Installer distro detection (ZOTHOS / Parrot / Debian / unsupported) without root."""
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
CASES = [
    ('ID=parrot\nID_LIKE=debian\nPRETTY_NAME="Parrot Security 6.4"\n', "parrot"),
    ('ID=zothos\nID_LIKE=debian\nNAME="ZOTHOS"\nVERSION_CODENAME=trixie\n', "zothos"),
    ('ID=debian\nNAME="ZOTHOS"\nVERSION_CODENAME=trixie\n', "zothos"),
    ('ID=debian\nNAME="Debian GNU/Linux"\nVERSION_CODENAME=trixie\n', "debian"),
    ('ID=fedora\nNAME="Fedora"\n', None),
]


@pytest.mark.parametrize("osr,expected", CASES)
def test_detect(tmp_path, osr, expected):
    f = tmp_path / "os-release"
    f.write_text(osr)
    r = subprocess.run(["bash", str(ROOT / "install.sh"), "--detect-only"], env={"OS_RELEASE": str(f), "PATH": "/usr/bin:/bin"},
                       capture_output=True, text=True)
    if expected is None:
        assert r.returncode != 0 and "unsupported" in r.stdout
    else:
        assert r.returncode == 0 and r.stdout.strip().splitlines()[-1] == expected
