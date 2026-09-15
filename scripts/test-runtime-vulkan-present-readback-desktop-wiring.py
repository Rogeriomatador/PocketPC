#!/usr/bin/env python3
"""Static guard for the v51 one-shot Vulkan-to-desktop delivery path.

This validates source wiring only. It does not execute Wine, Vulkan, Android
presentation, Roblox, or any physical-device test.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = (
    ROOT
    / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayExecutionController.kt"
)
SESSION = (
    ROOT
    / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplaySessionController.kt"
)
BRIDGE = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDesktopBridge.kt"
EVIDENCE = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeGraphicsEvidenceLog.kt"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"FAIL {label}: missing {needle!r}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise SystemExit(f"FAIL {label}: forbidden {needle!r}")


def main() -> None:
    controller = CONTROLLER.read_text(encoding="utf-8")
    session = SESSION.read_text(encoding="utf-8")
    bridge = BRIDGE.read_text(encoding="utf-8")
    evidence = EVIDENCE.read_text(encoding="utf-8")

    require(
        controller,
        "private data class RuntimeDisplayGraphicsTarget(",
        "exact target identity",
    )
    require(
        controller,
        "windowId = window.windowId",
        "target captures exact Win32 window",
    )
    require(
        controller,
        "graphics.awaitGpuQueueSignalProbe(",
        "stage 5 gate",
    )
    require(
        controller,
        "graphics.awaitPresentCopyCompletion(",
        "stage 6 gate",
    )
    require(
        controller,
        "graphics.consumePresentCopyOnAndroidHost(",
        "Android host readback gate",
    )
    require(
        controller,
        "desktopMultiplexer.presentExternalVulkanFrame(",
        "desktop delivery call",
    )
    require(
        controller,
        "windowId = target.windowId",
        "delivery preserves exact window ownership",
    )
    require(
        controller,
        "RuntimeDisplayExternalFrameIdentity(",
        "PVI1 identity preserved into compositor",
    )
    require(
        session,
        "compositor.applyExternalVulkanFrame(",
        "session compositor injection",
    )
    require(
        session,
        "publishSnapshot()",
        "session republishes compositor state",
    )
    require(
        session,
        "RuntimeGraphicsEvidenceLog.desktopModelFrameDelivered(",
        "session records post-snapshot model delivery",
    )
    require(
        bridge,
        "ownerForLocked(",
        "bridge exact owner lookup",
    )
    require(
        evidence,
        '"DESKTOP_MODEL_FRAME_DELIVERED"',
        "desktop model delivery evidence event",
    )
    require(
        evidence,
        '" model_delivery=1"',
        "model-only evidence classification",
    )
    require(
        evidence,
        '" host_visible_frame=0"',
        "physical visibility stays fail-closed",
    )
    require(
        evidence,
        '" physical_validated=0"',
        "physical validation stays false",
    )

    # The source pipeline must remain fail-closed about physical presentation.
    for text, label in (
        (controller, "controller"),
        (session, "session"),
        (bridge, "bridge"),
        (evidence, "evidence"),
    ):
        forbid(text, "hostVisiblePresentValidated = true", label)
        forbid(text, "robloxExecuted = true", label)
        forbid(text, "robloxRendered = true", label)
        forbid(text, "robloxPlayable = true", label)

    print("PASS static Vulkan stage5->stage6->readback->desktop wiring")
    print("EVIDENCE=STATICALLY_VALIDATED_ONLY")
    print("PHYSICAL=NOT_EXECUTED")
    print("ROBLOX=NOT_EXECUTED")


if __name__ == "__main__":
    main()
