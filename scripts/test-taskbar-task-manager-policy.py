#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

FILES = {
    "models": ROOT / "app/src/main/java/dev/pocketpc/core/desktop/DesktopModels.kt",
    "controller": ROOT / "app/src/main/java/dev/pocketpc/core/desktop/DesktopController.kt",
    "chrome": ROOT / "app/src/main/java/dev/pocketpc/core/ui/DesktopChrome.kt",
    "host": ROOT / "app/src/main/java/dev/pocketpc/core/ui/PocketPcApp.kt",
    "manager": ROOT / "app/src/main/java/dev/pocketpc/core/ui/TaskManagerApp.kt",
    "processes": ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeProcessSupervisor.kt",
}


def main() -> int:
    failures: list[str] = []
    text: dict[str, str] = {}

    for name, path in FILES.items():
        if not path.is_file():
            failures.append(f"missing source: {path.relative_to(ROOT)}")
            continue
        text[name] = path.read_text(encoding="utf-8-sig")

    checks = {
        "models": (
            'TASK_MANAGER("Gerenciador de Tarefas"',
            "DesktopApp.TASK_MANAGER ->",
        ),
        "controller": (
            "contextMenuAnchorX",
            "contextMenuAnchorY",
            "fun openContextMenu(",
            "anchorX: Int? = null",
            "anchorY: Int? = null",
        ),
        "host": (
            "DesktopApp.TASK_MANAGER ->",
            "TaskManagerApp(",
            "desktopSecondaryClickAt",
            "anchorX =",
            "anchorY =",
        ),
        "chrome": (
            "TaskbarAnchoredPopup(",
            "TaskbarPopupPositionProvider",
            "DesktopContextPopupPositionProvider",
            "TaskbarSystemMenu(",
            "desktopSecondaryClickAt",
            '"Gerenciador de Tarefas"',
            '"Mostrar no Gerenciador de Tarefas"',
            '"Encaixar à esquerda"',
            '"Encaixar à direita"',
            '"Fechar janela"',
            "PopupProperties(",
            "dismissOnClickOutside",
            "positionInRoot()",
        ),
        "manager": (
            "fun TaskManagerApp(",
            "TaskManagerSection.APPLICATIONS",
            "TaskManagerSection.PROCESSES",
            "RuntimeProcessRegistry.snapshots()",
            "Process.myPid()",
            '"Finalizar tarefa"',
            '"Forçar encerramento"',
            '"protegido"',
            "force = false",
            "force = true",
        ),
        "processes": (
            "data class RuntimeProcessSnapshot",
            "object RuntimeProcessRegistry",
            "internal fun register(",
            "internal fun unregister(",
            "fun snapshots()",
            "fun terminate(",
            "RuntimeProcessRegistry",
        ),
    }

    for name, sentinels in checks.items():
        source = text.get(name, "")
        for sentinel in sentinels:
            if sentinel not in source:
                failures.append(f"{name}: missing sentinel: {sentinel}")

    chrome = text.get("chrome", "")
    start = chrome.find("fun TaskbarV2(")
    end = chrome.find("@Composable\nprivate fun UpdateAttentionChip", start)
    if start < 0 or end <= start:
        failures.append("cannot isolate TaskbarV2 section")
    else:
        taskbar = chrome[start:end]
        if "DropdownMenu(" in taskbar:
            failures.append(
                "taskbar regressed to detached DropdownMenu positioning"
            )
        if "Popup(" not in taskbar:
            failures.append("taskbar anchored popup implementation missing")

    manager = text.get("manager", "")
    if (
        "RuntimeProcessRegistry" not in manager
        or ".terminate(" not in manager
    ):
        failures.append("Task Manager cannot terminate supervised runtime processes")
    if "window.app !=" not in manager or "DesktopApp.TASK_MANAGER" not in manager:
        failures.append("Task Manager self-window protection missing")

    processes = text.get("processes", "")
    if "process.destroyForcibly()" not in processes:
        failures.append("force-termination path missing")
    if "entries.remove(id)" not in processes:
        failures.append("runtime process registry cleanup missing")

    if failures:
        print("TASKBAR_TASK_MANAGER_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("TASKBAR_TASK_MANAGER_POLICY_OK")
    print("anchored_taskbar_menu=true")
    print("anchored_workspace_menu=true")
    print("runtime_process_termination=true")
    print("device_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
