#!/usr/bin/env python3
"""Static policy lock for the PocketPC Wine v51 pre-Present copy package."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: str) -> str:
    candidate = ROOT / path
    if not candidate.is_file():
        raise SystemExit(f"WINE_V51_INTEGRATION_FILE_MISSING:{path}")
    return candidate.read_text(encoding="utf-8")


def require(source: str, needle: str, label: str) -> None:
    if needle not in source:
        raise SystemExit(f"WINE_V51_INTEGRATION_MISSING:{label}")


def main() -> int:
    helper_h = text(
        "third_party/wine/pocketpc-display-bridge/pocketpc_guest_present_copy.h"
    )
    helper_c = text(
        "third_party/wine/pocketpc-display-bridge/pocketpc_guest_present_copy.c"
    )
    ack_h = text(
        "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_ack.h"
    )
    ack_c = text(
        "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_ack.c"
    )
    stage = text("scripts/prepare-wine-pocketpc-pre-present-copy.py")
    copy_ack_stage = text("scripts/prepare-wine-pocketpc-present-copy-ack.py")
    compile_fix_stage = text("scripts/prepare-wine-pocketpc-v51-compile-fix.py")
    composite = text("scripts/prepare-wine-pocketpc-driver-v51.py")
    build = text("scripts/build-wine-x86_64-v51.py")
    workflow = text(".github/workflows/wine-x86_64-build.yml")
    runtime = text(
        "app/src/main/java/dev/pocketpc/core/runtime/"
        "PocketPcWinePresentBridgeContract.kt"
    )
    roblox = text(
        "app/src/main/java/dev/pocketpc/core/runtime/RobloxGraphicsPreflight.kt"
    )
    roblox_environment = text(
        "app/src/main/java/dev/pocketpc/core/runtime/"
        "RobloxGraphicsDiagnosticEnvironment.kt"
    )
    roblox_launch = text(
        "app/src/main/java/dev/pocketpc/core/runtime/RobloxInstalledLaunchPlan.kt"
    )
    copy_ack_host = text(
        "app/src/main/java/dev/pocketpc/core/runtime/GraphicsPresentCopyAckHost.kt"
    )
    copy_ack_native = text("app/src/main/cpp/graphics_present_copy_ack.cpp")
    cmake = text("app/src/main/cpp/CMakeLists.txt")
    physical_capture = text("scripts/capture-graphics-runtime-evidence-windows.ps1")

    require(helper_h, "original semaphores a second time", "semaphore_consumption_contract")
    require(helper_c, "VK_IMAGE_LAYOUT_PRESENT_SRC_KHR", "source_present_layout")
    require(helper_c, "VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL", "source_transfer_layout")
    require(helper_c, "VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL", "destination_transfer_layout")
    require(helper_c, "VK_QUEUE_FAMILY_EXTERNAL", "external_queue_family")
    require(helper_c, "device->p_vkCmdCopyImage", "gpu_copy")
    require(helper_c, "submit_info.pWaitSemaphores = wait_semaphores", "original_waits")
    require(
        helper_c,
        "submit_info.pSignalSemaphores = &submission->present_wait_semaphore",
        "replacement_signal",
    )

    require(ack_h, "PGA_STAGE_PRESENT_COPY_COMPLETED 6u", "pga_stage6_header")
    require(ack_c, "stage <= PGA_STAGE_PRESENT_COPY_COMPLETED", "pga_stage6_sender_range")

    require(stage, "#define WINE_VULKAN_DRIVER_VERSION 51", "abi_51")
    require(stage, "VK_IMAGE_USAGE_TRANSFER_SRC_BIT", "swapchain_transfer_source")
    require(stage, "present_info->pWaitSemaphores = &pocketpc_present_wait", "present_wait_replacement")
    require(stage, "POCKETPC_VULKAN_PRESENT_COPY_DIAGNOSTIC=1", "diagnostic_gate")
    require(stage, '"pixelCopyImplemented": True', "copy_source_implemented")
    require(stage, '"pixelCopyExecuted": False', "copy_execution_fail_closed")
    require(stage, '"hostVisiblePresentImplemented": False', "visible_fail_closed")
    require(stage, '"robloxExecuted": False', "roblox_fail_closed")

    require(copy_ack_stage, "PGA_STAGE_PRESENT_COPY_COMPLETED", "stage6_dispatch")
    require(copy_ack_stage, "stage5MustSucceedBeforeStage6", "stage6_after_stage5_contract")
    require(copy_ack_stage, '"copyCompletedAckExecuted": False', "stage6_execution_fail_closed")

    require(compile_fix_stage, 'BAD_FORMAT = "pocketpc_guest_resource.descriptor.format"', "compile_fix_bad_format_guard")
    require(compile_fix_stage, 'GOOD_FORMAT = "pocketpc_guest_resource.descriptor.pixel_format"', "compile_fix_pixel_format_contract")
    require(compile_fix_stage, "text.count(BAD_FORMAT) != 2", "compile_fix_exact_rewrite_count")
    require(compile_fix_stage, '"descriptorPixelFormatContractUsed": True', "compile_fix_evidence_pixel_format")
    require(compile_fix_stage, '"descriptorFormatReferencesRewritten": 2', "compile_fix_evidence_rewrite_count")
    require(compile_fix_stage, '"compiled": False', "compile_fix_build_fail_closed")
    require(compile_fix_stage, '"runtimeExecuted": False', "compile_fix_runtime_fail_closed")
    require(compile_fix_stage, '"robloxExecuted": False', "compile_fix_roblox_fail_closed")

    require(composite, "prepare-wine-pocketpc-driver-v50.py", "v50_stage")
    require(composite, "prepare-wine-pocketpc-pre-present-copy.py", "v51_stage")
    require(composite, "prepare-wine-pocketpc-present-copy-ack.py", "stage6_composite_stage")
    require(composite, "prepare-wine-pocketpc-v51-compile-fix.py", "compile_fix_composite_stage")
    require(composite, "COMPILE_FIX_PREPARER", "compile_fix_composite_binding")
    require(composite, "require_compile_fix", "compile_fix_evidence_validation")
    require(composite, '"presentCopyCompletedAck"', "stage6_combined_evidence")
    require(composite, '"compileSourceNormalization"', "compile_fix_combined_evidence")
    require(composite, '"descriptorPixelFormatContractUsed": True', "combined_pixel_format_contract")
    require(composite, '"descriptorFormatReferencesRewritten": 2', "combined_format_rewrite_count")
    require(build, "module.DRIVER_PREPARER = V51_PREPARER", "build_preparer_override")
    require(workflow, "python3 scripts/build-wine-x86_64-v51.py", "workflow_v51_build")
    require(workflow, "python3 scripts/test-wine-x86_64-v51-integration.py", "workflow_v51_policy")

    require(runtime, "const val privateWineVulkanAbi = 51", "runtime_abi_51")
    require(runtime, "const val pixelCopyImplemented = true", "runtime_copy_implemented")
    require(runtime, "const val pixelCopyExecuted = false", "runtime_copy_not_executed")
    require(runtime, "const val androidVisiblePresentImplemented = false", "runtime_visible_false")
    require(runtime, "const val robloxExecuted = false", "runtime_roblox_false")
    require(roblox, "PocketPcWinePresentBridgeContract.pixelCopyExecuted", "roblox_copy_execution_gate")
    require(roblox, "PocketPcWinePresentBridgeContract.readyForRobloxGraphics()", "roblox_readiness_gate")

    require(
        roblox_environment,
        'PRESENT_COPY_DIAGNOSTIC =\n        "POCKETPC_VULKAN_PRESENT_COPY_DIAGNOSTIC"',
        "roblox_copy_diagnostic_environment",
    )
    require(
        roblox_environment,
        'put(PRESENT_COPY_DIAGNOSTIC, "1")',
        "roblox_copy_diagnostic_enabled",
    )
    require(
        roblox_launch,
        "enableRobloxGraphicsDiagnostics = false",
        "winecfg_diagnostics_disabled",
    )
    require(
        roblox_launch,
        "enableRobloxGraphicsDiagnostics = true",
        "roblox_diagnostics_enabled",
    )
    require(
        roblox_launch,
        "RobloxGraphicsDiagnosticEnvironment",
        "roblox_environment_wiring",
    )

    require(copy_ack_native, "kPgaPresentCopyCompletedStage = 6", "android_native_stage6")
    require(copy_ack_native, "SOCK_SEQPACKET", "android_native_stage6_seqpacket")
    require(copy_ack_native, "had_control", "android_native_stage6_no_ancillary")
    require(copy_ack_native, "ack_resource_id != resource_id", "android_native_stage6_identity")
    require(cmake, "graphics_present_copy_ack.cpp", "android_stage6_compiled")
    require(copy_ack_host, "const val STAGE = 6", "android_kotlin_stage6")
    require(copy_ack_host, "exactSwapchainPixelsCopied", "android_copy_evidence")
    require(copy_ack_host, "returnedToExternalGeneral", "android_external_evidence")
    require(copy_ack_host, "androidVisibleFrame", "android_visible_nonclaim")
    require(copy_ack_host, "robloxGameplayValidated", "roblox_nonclaim")

    require(physical_capture, "schemaVersion = 3", "physical_evidence_schema_v3")
    require(
        physical_capture,
        'prePresentCopySubmitted = "POCKETPC_VULKAN_PRESENT_COPY stage=copy_submitted"',
        "copy_submitted_marker",
    )
    require(
        physical_capture,
        'prePresentCopyCompleted = "POCKETPC_VULKAN_PRESENT_COPY stage=copy_queue_completed"',
        "copy_completed_marker",
    )
    require(
        physical_capture,
        "exactSwapchainPixelCopyEvidence = $copyPixelsEvidence",
        "copy_completion_derived_evidence",
    )
    require(
        physical_capture,
        "androidVisibleFrameEvidence = $false",
        "visible_frame_stays_fail_closed",
    )
    require(
        physical_capture,
        "robloxGameplayEvidence = $false",
        "roblox_gameplay_stays_fail_closed",
    )

    print("WINE_X86_64_V51_INTEGRATION_POLICY_OK_NOT_EXECUTED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
