#!/usr/bin/env python3
"""Static guard for exact-window Vulkan frame refresh wiring.

This test intentionally validates source wiring only. It is not evidence that
Android physically presented a frame, nor that Wine/Roblox executed.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "app/src/main/java/dev/pocketpc/core/ui/RuntimeDisplayFramePreview.kt"
WINDOW_LAYER = ROOT / "app/src/main/java/dev/pocketpc/core/ui/RuntimeDesktopWindowLayer.kt"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"FAIL {label}: missing {needle!r}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise SystemExit(f"FAIL {label}: forbidden {needle!r}")


def main() -> None:
    preview = PREVIEW.read_text(encoding="utf-8")
    layer = WINDOW_LAYER.read_text(encoding="utf-8")

    require(
        preview,
        "frameId: Long = 0L",
        "preview frame identity parameter",
    )
    require(
        preview,
        "RuntimeDisplayExternalFrameIdentity? = null",
        "preview external identity parameter",
    )
    require(
        preview,
        "frameId,\n            externalFrameIdentity,",
        "bitmap remember invalidation keys",
    )

    require(
        layer,
        "frameId = window.frameId",
        "desktop layer forwards legacy frame identity",
    )
    require(
        layer,
        "externalFrameIdentity =\n                                        window.externalFrameIdentity",
        "desktop layer forwards Vulkan frame identity",
    )

    # A source-wiring regression guard must never promote physical evidence.
    for text, label in ((preview, "preview"), (layer, "desktop layer")):
        forbid(text, "hostVisiblePresentValidated = true", label)
        forbid(text, "robloxExecuted = true", label)
        forbid(text, "robloxRendered = true", label)
        forbid(text, "robloxPlayable = true", label)

    print("PASS static Vulkan Compose refresh wiring")
    print("EVIDENCE=STATICALLY_VALIDATED_ONLY")
    print("PHYSICAL=NOT_EXECUTED")


if __name__ == "__main__":
    main()
