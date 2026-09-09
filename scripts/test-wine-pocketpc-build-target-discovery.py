#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile


ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "scripts/build-wine-pocketpc-driver.py"


def load_harness():
    spec = importlib.util.spec_from_file_location(
        "pocketpc_wine_driver_build_harness",
        HARNESS,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("BUILD_HARNESS_IMPORT_FAILED")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_makefile(build: Path, text: str) -> Path:
    makefile = build / "Makefile"
    makefile.write_text(text, encoding="utf-8")
    return makefile


def assert_equal(actual, expected, label: str) -> None:
    if actual != expected:
        raise AssertionError(
            f"{label}: expected={expected!r} actual={actual!r}"
        )


def main() -> int:
    harness = load_harness()

    aggregate = "dlls/winepocketpc.drv/all"
    pe = "dlls/winepocketpc.drv/winepocketpc.drv"
    unixlib = "dlls/winepocketpc.drv/winepocketpc.so"

    with tempfile.TemporaryDirectory(
        prefix="pocketpc-wine-target-test-"
    ) as temp:
        root = Path(temp)

        case1 = root / "aggregate"
        case1.mkdir()
        make1 = write_makefile(
            case1,
            f"""{pe}:
	@:
{unixlib}:
	@:
{aggregate}: {pe} {unixlib}
	@:
.PHONY: {aggregate}
""",
        )
        selected, discovered = harness.discover_build_targets(
            case1,
            make1,
        )
        assert_equal(
            selected,
            [aggregate],
            "aggregate target selection",
        )
        if aggregate not in discovered:
            raise AssertionError(
                "aggregate target was not discovered"
            )

        case2 = root / "split"
        case2.mkdir()
        make2 = write_makefile(
            case2,
            f"""{pe}:
	@:
{unixlib}:
	@:
""",
        )
        selected, discovered = harness.discover_build_targets(
            case2,
            make2,
        )
        assert_equal(
            selected,
            [pe, unixlib],
            "split target selection",
        )
        if pe not in discovered or unixlib not in discovered:
            raise AssertionError(
                "split targets were not both discovered"
            )

        case3 = root / "incomplete"
        case3.mkdir()
        make3 = write_makefile(
            case3,
            f"""{pe}:
	@:
""",
        )
        selected, discovered = harness.discover_build_targets(
            case3,
            make3,
        )
        assert_equal(
            selected,
            [],
            "incomplete target rejection",
        )
        if pe not in discovered or unixlib in discovered:
            raise AssertionError(
                "incomplete target diagnostics are incorrect"
            )

        case4 = root / "existing-directory"
        case4.mkdir()
        (case4 / "dlls/winepocketpc.drv").mkdir(
            parents=True,
        )
        make4 = write_makefile(
            case4,
            "all:\n\t@:\n",
        )
        selected, discovered = harness.discover_build_targets(
            case4,
            make4,
        )
        assert_equal(
            selected,
            [],
            "existing directory must not become a make target",
        )
        if aggregate in discovered:
            raise AssertionError(
                "aggregate target falsely discovered from directory"
            )

    print("WINE_POCKETPC_BUILD_TARGET_DISCOVERY_OK")
    print("runtime_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
