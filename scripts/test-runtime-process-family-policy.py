#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "third_party/wine/POCKETPC_DISPLAY_BRIDGE_PROTOCOL.json"
AUDIT = ROOT / "third_party/wine/ANDROID_DRIVER_REUSE.json"
SUPERVISOR = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeProcessSupervisor.kt"
PROOT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/ProotExecutionController.kt"
DISPLAY = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayExecutionController.kt"
TASK_MANAGER = ROOT / "app/src/main/java/dev/pocketpc/core/ui/TaskManagerApp.kt"
TREE_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/runtime/RuntimeProcTreeTest.kt"


def require(
    failures: list[str],
    label: str,
    text: str,
    markers: tuple[str, ...],
) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(
                f"{label} missing: {marker}"
            )


def main() -> int:
    failures: list[str] = []

    try:
        protocol = json.loads(
            PROTOCOL.read_text(encoding="utf-8"),
        )
        audit = json.loads(
            AUDIT.read_text(encoding="utf-8"),
        )
    except Exception as error:
        print(
            "RUNTIME_PROCESS_FAMILY_POLICY_FAILED",
            file=sys.stderr,
        )
        print(f"- json: {error}", file=sys.stderr)
        return 1

    expected_true = (
        "processFamilySupervisionImplemented",
        "displayPeerProcessFamilyAssociationImplemented",
        "taskManagerProcessFamilyUiImplemented",
        "taskManagerIndividualProcessControlImplemented",
    )
    expected_false = (
        "processFamilySupervisionSoftwareTestExecuted",
        "displayPeerProcessFamilyAssociationSoftwareTestExecuted",
        "taskManagerProcessFamilyUiSoftwareTestExecuted",
        "taskManagerIndividualProcessControlSoftwareTestExecuted",
    )

    for label, document in (
        ("protocol", protocol),
        ("audit", audit),
    ):
        gates = document.get("gates") or {}
        for key in expected_true:
            if gates.get(key) is not True:
                failures.append(
                    f"{label} expected true gate: {key}"
                )
        for key in expected_false:
            if gates.get(key) is not False:
                failures.append(
                    f"{label} expected false gate: {key}"
                )

    supervisor = SUPERVISOR.read_text(
        encoding="utf-8",
    )
    proot = PROOT.read_text(
        encoding="utf-8",
    )
    display = DISPLAY.read_text(
        encoding="utf-8",
    )
    manager = TASK_MANAGER.read_text(
        encoding="utf-8",
    )
    tree_test = TREE_TEST.read_text(
        encoding="utf-8",
    )

    require(
        failures,
        "process family identity",
        supervisor,
        (
            "data class RuntimeProcProcess",
            "startTimeTicks",
            'File("/proc")',
            '"/proc/$pid"',
            "RuntimeProcTree.family",
            "RuntimeProcTree.depths",
            "seedLiveRoot",
            "ROOT_EXIT_HANDOFF_GRACE_MILLIS",
        ),
    )
    require(
        failures,
        "pid reuse safety",
        supervisor,
        (
            "current.startTimeTicks ==",
            "member.startTimeTicks",
            "android.os.Process",
            ".myPid()",
        ),
    )
    require(
        failures,
        "family termination",
        supervisor,
        (
            "Os.kill(",
            "OsConstants.SIGTERM",
            "OsConstants.SIGKILL",
            "compareByDescending",
            "destroyForcibly()",
            "terminateMember(",
            "activeRegistryId",
            "stopActive()",
        ),
    )
    require(
        failures,
        "launcher family retention",
        supervisor,
        (
            "markRootExited",
            "rootExitedAtMillis",
            "familyRetained",
            "observeFamily",
            "associateMember",
        ),
    )
    require(
        failures,
        "display PID association",
        proot + display,
        (
            "associateActiveFamilyPid",
            "window.windowId",
            ".ushr(32)",
            "processPid >",
            "Int.MAX_VALUE",
        ),
    )
    require(
        failures,
        "Task Manager family UI",
        manager,
        (
            "descendantCount",
            "familyResidentMemoryBytes",
            "familyThreadCount",
            "familyPids",
            "launcher encerrado • família ativa",
            "PIDs da família:",
            "Finalizar família",
            "Forçar família",
            "Subprocessos",
            "terminateMember(",
        ),
    )
    require(
        failures,
        "process tree unit tests",
        tree_test,
        (
            "familyRejectsPidReuseByStartTime",
            "knownReparentedChildRemainsAndDiscoversGrandchild",
            "depthsPlaceDeepestDescendantsBeforeRootForTermination",
            "unrelatedProcessesNeverJoinFamily",
        ),
    )

    contract = (
        protocol.get("multiProcessDisplay")
        or {}
    ).get("processFamilySupervision") or {}
    if (
        contract.get("status")
        != "IMPLEMENTED_STATICALLY_NOT_EXECUTED"
    ):
        failures.append(
            "process family contract status changed"
        )

    if failures:
        print(
            "RUNTIME_PROCESS_FAMILY_POLICY_FAILED",
            file=sys.stderr,
        )
        for failure in failures:
            print(
                "- " + failure,
                file=sys.stderr,
            )
        return 1

    print("RUNTIME_PROCESS_FAMILY_POLICY_OK")
    print("pid_identity=pid_plus_proc_start_ticks")
    print("termination_order=deepest_descendant_first")
    print("display_pid_association=window_id_high32")
    print("software_test_execution=false")
    print("physical_execution=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
