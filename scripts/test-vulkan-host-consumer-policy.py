#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "app/src/main/cpp/vulkan_external_image_host_consumer.cpp"
CMAKE = ROOT / "app/src/main/cpp/CMakeLists.txt"
KOTLIN = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/VulkanExternalImageHostConsumer.kt"
ORCHESTRATOR = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsSessionOrchestrator.kt"
STAGE6 = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GraphicsPresentCopyAckHost.kt"
EVIDENCE = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeGraphicsEvidenceLog.kt"
UNIT = ROOT / "app/src/test/java/dev/pocketpc/core/runtime/VulkanExternalImageHostConsumerTest.kt"


def require(
    failures: list[str],
    label: str,
    text: str,
    markers: tuple[str, ...],
) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    paths = {
        "native": NATIVE,
        "cmake": CMAKE,
        "kotlin": KOTLIN,
        "orchestrator": ORCHESTRATOR,
        "stage6": STAGE6,
        "evidence": EVIDENCE,
        "unit": UNIT,
    }
    texts: dict[str, str] = {}
    for label, path in paths.items():
        try:
            texts[label] = path.read_text(encoding="utf-8")
        except Exception as error:
            failures.append(f"{label} unreadable: {error}")

    if failures:
        return fail(failures)

    require(
        failures,
        "native consumer",
        texts["native"],
        (
            "VK_QUEUE_FAMILY_EXTERNAL",
            "VK_IMAGE_LAYOUT_GENERAL",
            "VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL",
            "vkCmdCopyImageToBuffer(",
            "VK_ACCESS_TRANSFER_READ_BIT",
            "VK_ACCESS_HOST_READ_BIT",
            "vkQueueSubmit(",
            "vkQueueWaitIdle(",
            "vkInvalidateMappedMemoryRanges(",
            "vkMapMemory(",
            "returned_external=1;visible_frame=0",
            "acquired=0;returned_external=0;visible_frame=0",
            "kFirstGuestSignalValue = 1u",
            "kMaxReadbackBytes",
        ),
    )
    native = texts["native"]
    acquire_index = native.find("acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL")
    copy_index = native.find("vkCmdCopyImageToBuffer(")
    release_index = native.find("release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL")
    if not (0 <= acquire_index < copy_index < release_index):
        failures.append("native consumer must acquire EXTERNAL before copy and release after copy")

    require(
        failures,
        "CMake",
        texts["cmake"],
        ("vulkan_external_image_host_consumer.cpp",),
    )

    require(
        failures,
        "Kotlin consumer",
        texts["kotlin"],
        (
            "data class VulkanExternalImageHostReadback",
            "val androidAcquireExecuted: Boolean",
            "val androidVisibleFrame: Boolean",
            "get() = false",
            "!visibleFrame",
            "it.bytes == expectedBytes",
            'fields["visible_frame"] == "1"',
            "VULKAN_HOST_CONSUMER_FAILED",
        ),
    )

    require(
        failures,
        "stage 6 receiver",
        texts["stage6"],
        (
            "const val STAGE = 6",
            "exactSwapchainPixelsCopied",
            "returnedToExternalGeneral",
            "val androidVisibleFrame: Boolean",
            "get() = false",
            "val robloxGameplayValidated: Boolean",
        ),
    )

    require(
        failures,
        "orchestrator",
        texts["orchestrator"],
        (
            "PRESENT_COPY_COMPLETED",
            "ANDROID_HOST_READBACK_COMPLETED",
            "fun awaitPresentCopyCompletion(",
            "fun consumePresentCopyOnAndroidHost(",
            "check(offer.gpuQueueSignalObserved)",
            "check(offer.presentCopyCompleted)",
            "readback.androidAcquireExecuted",
            "hostVisibleFrame = false",
            "robloxValidated = false",
        ),
    )
    orchestrator = texts["orchestrator"]
    stage5_index = orchestrator.find("fun awaitGpuQueueSignalProbe(")
    stage6_index = orchestrator.find("fun awaitPresentCopyCompletion(")
    consume_index = orchestrator.find("fun consumePresentCopyOnAndroidHost(")
    if not (0 <= stage5_index < stage6_index < consume_index):
        failures.append("orchestrator gate order must remain stage5 -> stage6 -> Android consume")

    require(
        failures,
        "evidence log",
        texts["evidence"],
        (
            "PRESENT_COPY_COMPLETED",
            "ANDROID_HOST_READBACK_COMPLETED",
            "returned_external_general=1",
            "host_visible_frame=0",
            "roblox_validated=0",
        ),
    )

    require(
        failures,
        "unit test",
        texts["unit"],
        (
            "successfulReadbackProvesAcquireButNotVisibleFrame",
            "visibleFrameClaimIsRejectedByReadbackGate",
            "mismatchedIdentityOrByteCountIsRejected",
            "assertFalse(result.androidVisibleFrame)",
            "visible_frame=1",
            "assertNull(",
        ),
    )

    for label in ("native", "kotlin", "orchestrator", "evidence"):
        text = texts[label]
        forbidden = (
            "robloxValidated = true",
            "robloxGameplayValidated = true",
            "hostVisibleFrame = true",
            "androidVisibleFrame = true",
        )
        for marker in forbidden:
            if marker in text:
                failures.append(f"{label} contains forbidden promotion: {marker}")

    if failures:
        return fail(failures)

    print("VULKAN_HOST_CONSUMER_POLICY_OK")
    print("stage6_required=true")
    print("external_acquire_required=true")
    print("host_readback_gate=true")
    print("visible_frame=false")
    print("roblox_validated=false")
    print("execution_evidence=false")
    return 0


def fail(failures: list[str]) -> int:
    print("VULKAN_HOST_CONSUMER_POLICY_FAILED", file=sys.stderr)
    for failure in failures:
        print("- " + failure, file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
