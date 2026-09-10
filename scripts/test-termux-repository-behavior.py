#!/usr/bin/env python3
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CHECK = ROOT / "scripts" / "termux-repository-check.sh"


def run(prefix: Path) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["PREFIX"] = str(prefix)
    env["TERMUX_VERSION"] = "googleplay.test"
    return subprocess.run(
        ["bash", str(CHECK), "--require-compatible"],
        cwd=ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )


with tempfile.TemporaryDirectory(prefix="pocketpc-termux-repo-") as raw:
    prefix = Path(raw)
    apt = prefix / "etc" / "apt"
    sources_d = apt / "sources.list.d"
    sources_d.mkdir(parents=True)

    (sources_d / "termux.sources").write_text(
        "Types: deb\n"
        "URIs: https://termux.net\n"
        "Suites: stable\n"
        "Components: main\n",
        encoding="utf-8",
    )

    good = run(prefix)
    assert good.returncode == 0, good.stdout
    assert "TERMUX_REPOSITORY_GOOGLE_PLAY_OK" in good.stdout, good.stdout
    assert "termux.sources" in good.stdout, good.stdout
    assert "https://termux.net" in good.stdout, good.stdout

    (apt / "sources.list").write_text(
        "deb https://packages.termux.dev/apt/termux-main stable main\n",
        encoding="utf-8",
    )

    mixed = run(prefix)
    assert mixed.returncode == 15, mixed.stdout
    assert "TERMUX_REPOSITORY_VARIANT_MIXED" in mixed.stdout, mixed.stdout
    assert "MIXED_GOOGLE_PLAY_AND_CLASSIC" in mixed.stdout, mixed.stdout
    assert "termux-repair-repository.sh --apply" in mixed.stdout, mixed.stdout

print("TERMUX_REPOSITORY_BEHAVIOR_OK")
print("google_play_deb822=recognized")
print("mixed_variant=blocked")
