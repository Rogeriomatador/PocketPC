#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest import mock


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
    sys.modules[spec.name] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(spec.name, None)
        raise
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

    assert_equal(
        harness.OFFICIAL_BUILD_TARGETS,
        (aggregate, pe, unixlib),
        "official target contract",
    )

    with tempfile.TemporaryDirectory(
        prefix="pocketpc-wine-target-test-"
    ) as temp:
        root = Path(temp)

        case1 = root / "aggregate"
        case1.mkdir()
        make1 = write_makefile(
            case1,
            f"""{pe}:
\t@:
{unixlib}:
\t@:
{aggregate}: {pe} {unixlib}
\t@:
.PHONY: {aggregate}
""",
        )
        result = harness.discover_build_targets(
            case1,
            make1,
        )
        assert_equal(
            result.selected,
            [aggregate],
            "aggregate target selection",
        )
        if aggregate not in result.discovered:
            raise AssertionError(
                "aggregate target was not discovered"
            )
        if result.selection_source not in {
            "make-database",
            "generated-makefile",
        }:
            raise AssertionError(
                "aggregate selection source missing"
            )

        case2 = root / "split"
        case2.mkdir()
        make2 = write_makefile(
            case2,
            f"""{pe}:
\t@:
{unixlib}:
\t@:
""",
        )
        result = harness.discover_build_targets(
            case2,
            make2,
        )
        assert_equal(
            result.selected,
            [pe, unixlib],
            "split target selection",
        )
        if (
            pe not in result.discovered
            or unixlib not in result.discovered
        ):
            raise AssertionError(
                "split targets were not both discovered"
            )

        case3 = root / "incomplete"
        case3.mkdir()
        make3 = write_makefile(
            case3,
            f"""{pe}:
\t@:
""",
        )
        result = harness.discover_build_targets(
            case3,
            make3,
        )
        assert_equal(
            result.selected,
            [],
            "incomplete target rejection",
        )
        if (
            pe not in result.discovered
            or unixlib in result.discovered
        ):
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
        result = harness.discover_build_targets(
            case4,
            make4,
        )
        assert_equal(
            result.selected,
            [],
            "existing directory must not become a make target",
        )
        if aggregate in result.discovered:
            raise AssertionError(
                "aggregate target falsely discovered from directory"
            )

        case5 = root / "make-query-one"
        case5.mkdir()
        make5 = write_makefile(
            case5,
            f"{aggregate}:\n\t@:\n",
        )
        fake_make_query = subprocess.CompletedProcess(
                args=["make"],
                returncode=1,
                stdout=f"{aggregate}:\n",
            )
        with mock.patch.object(
            harness.subprocess,
            "run",
            return_value=fake_make_query,
        ):
            result = harness.discover_build_targets(
                case5,
                make5,
            )
        assert_equal(
            result.selected,
            [aggregate],
            "make -q exit one remains usable",
        )
        assert_equal(
            result.database_exit_code,
            1,
            "make database exit one recorded",
        )
        assert_equal(
            result.selection_source,
            "make-database",
            "make database source retained",
        )

        case6 = root / "make-unavailable"
        case6.mkdir()
        make6 = write_makefile(
            case6,
            f"""{pe}:
\t@:
{unixlib}:
\t@:
""",
        )
        with mock.patch.object(
            harness.subprocess,
            "run",
            side_effect=FileNotFoundError(
                "make",
            ),
        ):
            result = harness.discover_build_targets(
                case6,
                make6,
            )
        assert_equal(
            result.selected,
            [pe, unixlib],
            "generated Makefile fallback",
        )
        assert_equal(
            result.selection_source,
            "generated-makefile",
            "fallback source",
        )
        if not (
            result.database_error
            and result.database_error.startswith(
                "MAKE_DATABASE_EXEC_FAILED:"
            )
        ):
            raise AssertionError(
                "make execution failure was not recorded"
            )

        case7 = root / "foreign-target"
        case7.mkdir()
        make7 = write_makefile(
            case7,
            """dlls/winepocketpc.drv/not-official:
\t@:
""",
        )
        with mock.patch.object(
            harness.subprocess,
            "run",
            side_effect=FileNotFoundError(
                "make",
            ),
        ):
            result = harness.discover_build_targets(
                case7,
                make7,
            )
        assert_equal(
            result.selected,
            [],
            "foreign target must stay rejected",
        )
        assert_equal(
            result.discovered,
            [],
            "foreign target must not enter diagnostics",
        )

    print("WINE_POCKETPC_BUILD_TARGET_DISCOVERY_OK")
    print("official_targets=aggregate_or_exact_pe_plus_unixlib")
    print("runtime_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
