#!/usr/bin/env python3
"""Static policy lock for the PocketPC Wine v52 fast driver-build path.

This test prevents regressions that could remove the compile normalization or
turn a driver-only software build into runtime/physical/Roblox evidence.
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPILE_FIX = ROOT / "scripts/prepare-wine-pocketpc-v52-compile-fix.py"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver-v52.py"
FAST_WORKFLOW = ROOT / ".github/workflows/wine-v52-driver-fast-build.yml"
PR_WORKFLOW = ROOT / ".github/workflows/wine-v52-pr-validation.yml"


def fail(message: str) -> None:
    raise SystemExit(f"FAIL Wine v52 fast-build policy: {message}")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        fail(f"{label} missing {needle!r}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        fail(f"{label} contains forbidden {needle!r}")


def main() -> None:
    compile_fix = COMPILE_FIX.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")
    fast_workflow = FAST_WORKFLOW.read_text(encoding="utf-8")
    pr_workflow = PR_WORKFLOW.read_text(encoding="utf-8")

    require(compile_fix, 'ABI_52 = "#define WINE_VULKAN_DRIVER_VERSION 52"', "compile fix ABI")
    require(compile_fix, 'GATE_DECLARATION = "static BOOL pocketpc_continuous_present_v52_enabled(void);"', "compile fix declaration")
    require(compile_fix, "if text.count(GATE_DECLARATION) != 0", "compile fix duplicate guard")
    require(compile_fix, "declaration_index < queue_index < definition_index", "compile fix ordering guard")
    require(compile_fix, '"continuousPresentGateForwardDeclaredBeforeQueueCallback": True', "compile fix evidence")
    require(compile_fix, '"compiled": False', "compile fix build fail-closed")
    require(compile_fix, '"runtimeExecuted": False', "compile fix runtime fail-closed")
    require(compile_fix, '"physicalVisibleFrame": False', "compile fix physical fail-closed")
    require(compile_fix, '"robloxExecuted": False', "compile fix Roblox fail-closed")

    require(preparer, "V52_COMPILE_FIX", "v52 preparer compile-fix stage")
    require(preparer, "run_stage(V52_COMPILE_FIX", "v52 preparer compile-fix execution")
    require(preparer, "require_compile_fix(compile_fix)", "v52 preparer compile-fix evidence validation")
    require(preparer, '"compileSourceNormalization"', "v52 combined normalization evidence")
    require(preparer, '"continuousPresentGateForwardDeclaredBeforeQueueCallback": True', "v52 combined forward declaration evidence")
    require(preparer, '"compiled": False', "v52 preparer build fail-closed")
    require(preparer, '"runtimeExecuted": False', "v52 preparer runtime fail-closed")
    require(preparer, '"physicalVisibleFrame": False', "v52 preparer physical fail-closed")
    require(preparer, '"robloxExecuted": False', "v52 preparer Roblox fail-closed")

    require(fast_workflow, "pull_request:", "fast-build PR trigger")
    require(fast_workflow, "prepare-wine-pocketpc-v52-compile-fix.py", "fast-build compile-fix coverage")
    require(fast_workflow, "Apply complete experimental v52 overlay", "fast-build prepared source")
    require(fast_workflow, "Build only experimental v52 winepocketpc driver", "fast-build driver-only step")
    require(fast_workflow, "audit-wine-pocketpc-driver-pe.py", "fast-build PE audit")
    require(fast_workflow, "verify-wine-pocketpc-driver-artifact-set.py", "fast-build artifact verifier")
    require(fast_workflow, "V52_DRIVER_ONLY_COMPILED_NOT_FULL_PACKAGE_NOT_RUNTIME_TESTED", "fast-build evidence status")
    require(fast_workflow, "'fullWinePackageBuilt': False", "fast-build full package fail-closed")
    require(fast_workflow, "'runtimeExecuted': False", "fast-build runtime fail-closed")
    require(fast_workflow, "'androidExecuted': False", "fast-build Android fail-closed")
    require(fast_workflow, "'physicalVisibleFrame': False", "fast-build physical fail-closed")
    require(fast_workflow, "'robloxExecuted': False", "fast-build Roblox fail-closed")
    forbid(fast_workflow, "'robloxExecuted': True", "fast-build workflow")
    forbid(fast_workflow, "'physicalVisibleFrame': True", "fast-build workflow")

    require(pr_workflow, "prepare-wine-pocketpc-v52-compile-fix.py", "full PR validation compile-fix coverage")
    require(pr_workflow, "Build pinned Wine 11 x86_64 with experimental v52 driver", "full PR package gate")
    require(pr_workflow, "Prove host Wine loads experimental v52 winepocketpc.drv", "full PR load gate")
    require(pr_workflow, "Prove host Wine v52 window surface and input round-trip", "full PR surface/input gate")

    print("PASS Wine v52 fast-build policy")
    print("V52_DRIVER_FAST_BUILD=REQUIRED")
    print("V52_FULL_PACKAGE=SEPARATE_GATE")
    print("RUNTIME=NOT_PROMOTED_BY_DRIVER_BUILD")
    print("PHYSICAL=NOT_EXECUTED")
    print("ROBLOX=NOT_EXECUTED")


if __name__ == "__main__":
    main()
