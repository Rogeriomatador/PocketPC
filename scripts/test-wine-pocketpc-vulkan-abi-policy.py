#!/usr/bin/env python3
"""Guard the PocketPC Wine Vulkan v49 ABI and its fail-closed visible path."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "third_party/wine/pocketpc-driver"
VULKAN = DRIVER / "vulkan.c"
MAIN = DRIVER / "pocketpcdrv_main.c"
MAKEFILE = DRIVER / "Makefile.in"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiContract.kt"
ARCH = ROOT / "third_party/wine/POCKETPC_VULKAN_WSI_ARCHITECTURE.json"


def need(text: str, marker: str, label: str) -> None:
    if marker not in text:
        raise SystemExit(f"POCKETPC_VULKAN_ABI_POLICY_MISSING:{label}")


def absent(text: str, marker: str, label: str) -> None:
    if marker in text:
        raise SystemExit(f"POCKETPC_VULKAN_ABI_POLICY_FORBIDDEN:{label}")


def main() -> int:
    vulkan = VULKAN.read_text(encoding="utf-8")
    main_source = MAIN.read_text(encoding="utf-8")
    makefile = MAKEFILE.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    arch = json.loads(ARCH.read_text(encoding="utf-8"))

    if arch.get("schemaVersion") != 7:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_ARCHITECTURE_SCHEMA_MISMATCH")
    if arch.get("status") != "VULKAN_TRANSPORT_PRIMITIVES_IMPLEMENTED_VISIBLE_WSI_NOT_IMPLEMENTED_NOT_EXECUTED":
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_ARCHITECTURE_STATUS_MISMATCH")

    need(main_source, ".pVulkanInit =\n        POCKETPC_VulkanInit,", "user-driver-vulkan-init")
    need(makefile, "\tvulkan.c \\", "vulkan-source-build")
    need(makefile, "\tpocketpc_graphics_session_client.c \\", "graphics-session-client-build")
    need(makefile, "\tpocketpc_graphics_ack.c \\", "graphics-ack-build")

    need(preparer, 'VULKAN_DRIVER_VERSION_47 = "#define WINE_VULKAN_DRIVER_VERSION 47"', "upstream-version-lock")
    need(preparer, 'VULKAN_DRIVER_VERSION_49 = "#define WINE_VULKAN_DRIVER_VERSION 49"', "pocketpc-version-patch")
    need(preparer, "p_vulkan_device_created", "overlay-device-created-patch")
    need(preparer, "p_vulkan_device_destroyed", "overlay-device-destroyed-patch")
    need(preparer, "p_vulkan_queue_presented", "overlay-present-queue-patch")
    need(preparer, "driver_funcs->p_vulkan_queue_presented( queue, res )", "win32u-present-callback-call")
    need(preparer, '"vulkanAbiDriverVersion": 49', "overlay-vulkan-version")
    need(preparer, '"presentQueueTimelineSignalSourceIntegrated": True', "overlay-present-queue-signal")
    need(preparer, '"presentQueueTimelineSignalExecuted": False', "overlay-present-queue-not-executed")

    need(contract, "WINE_VULKAN_DRIVER_VERSION =\n        49", "contract-version-49")
    need(contract, "PINNED_WINE_UPSTREAM_VULKAN_DRIVER_VERSION =\n        47", "contract-upstream-version-47")
    need(contract, "const val deviceLifecycleCallbacksImplemented =\n        true", "contract-device-lifecycle")
    need(contract, "const val presentQueueCallbackImplemented =\n        true", "contract-present-queue-callback")
    need(contract, "const val presentQueueTimelineSignalSourceIntegrated =\n        true", "contract-present-queue-signal")
    need(contract, "const val presentQueueTimelineSignalExecuted =\n        false", "contract-present-queue-not-executed")
    need(contract, "const val swapchainImageCaptureImplemented =\n        false", "contract-no-image-capture")
    need(contract, "const val hostVisibleFrameImplemented =\n        false", "contract-no-visible-frame")
    need(contract, "const val implemented =\n        false", "contract-visible-wsi-state")
    need(contract, '"VULKAN_WSI_NOT_IMPLEMENTED"', "contract-visible-wsi-blocker")

    for marker, label in (
        (".p_vulkan_surface_create = pocketpc_vulkan_surface_create,", "surface-callback"),
        (".p_get_physical_device_presentation_support =", "presentation-callback"),
        (".p_map_instance_extensions = pocketpc_map_instance_extensions,", "instance-map-callback"),
        (".p_map_device_extensions = pocketpc_map_device_extensions,", "device-map-callback"),
        (".p_vulkan_device_created = pocketpc_vulkan_device_created,", "device-created-callback"),
        (".p_vulkan_device_destroyed = pocketpc_vulkan_device_destroyed,", "device-destroyed-callback"),
        (".p_vulkan_queue_presented = pocketpc_vulkan_queue_presented,", "present-queue-callback"),
        ("static void pocketpc_vulkan_queue_presented", "present-queue-handler"),
        ("present_result != VK_SUCCESS && present_result != VK_SUBOPTIMAL_KHR", "present-success-gate"),
        ("pocketpc_guest_vulkan_timeline_signal_queue", "present-queue-timeline-signal"),
        ("same_queue_as_present=1", "same-present-queue-classification"),
        ("visible_present=0", "non-visible-present-classification"),
        ("PGA_STAGE_GPU_SIGNAL_SUBMITTED", "present-queue-ack-stage"),
        ("pocketpc_graphics_session_connect_from_environment", "live-pgh1-client"),
        ("pgt_receive_resource_offer", "pgt-resource-offer-receive"),
        ("pocketpc_external_image_fd_receive", "pvi1-receive"),
        ("pocketpc_guest_vulkan_import_external_image", "pvi1-active-device-import"),
        ("pocketpc_external_timeline_semaphore_fd_receive", "pvs1-receive"),
        ("pocketpc_guest_vulkan_timeline_import", "pvs1-active-device-import"),
        ('getenv("POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC")', "headless-env-gate"),
        ('strcmp(value, "1")', "headless-strict-value"),
        ("return VK_ERROR_INCOMPATIBLE_DRIVER;", "production-surface-fail-closed"),
        ("return VK_FALSE;", "production-presentation-fail-closed"),
        ("VkHeadlessSurfaceCreateInfoEXT", "headless-create-info"),
        ("instance->p_vkCreateHeadlessSurfaceEXT", "headless-create-call"),
        ("client_surface_create(", "headless-client-surface"),
        ("stage=headless_present_observed", "headless-present-observer"),
        ("extensions->has_VK_KHR_win32_surface", "headless-win32-mapping-input"),
        ("extensions->has_VK_EXT_headless_surface = 1", "headless-extension-map"),
        ("has_VK_KHR_external_memory_win32", "external-memory-win32"),
        ("has_VK_KHR_external_memory_fd = 1", "external-memory-fd"),
        ("has_VK_KHR_external_semaphore_win32", "external-semaphore-win32"),
        ("has_VK_KHR_external_semaphore_fd = 1", "external-semaphore-fd"),
        ("has_VK_KHR_external_fence_win32", "external-fence-win32"),
        ("has_VK_KHR_external_fence_fd = 1", "external-fence-fd"),
    ):
        need(vulkan, marker, label)

    for marker, label in (
        ("vkCreateAndroidSurfaceKHR", "raw-android-surface-shortcut"),
        ("has_VK_KHR_android_surface = 1", "android-surface-promotion"),
        ("ANativeWindow_fromSurface", "raw-anativewindow-shortcut"),
    ):
        absent(vulkan, marker, label)

    guard_pos = vulkan.find("if (!pocketpc_headless_diagnostic_enabled())")
    create_pos = vulkan.find("instance->p_vkCreateHeadlessSurfaceEXT")
    if guard_pos < 0 or create_pos < 0 or guard_pos > create_pos:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_HEADLESS_GUARD_ORDER_INVALID")

    wine = arch.get("wine") or {}
    if wine.get("upstreamVulkanDriverVersion") != 47:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_UPSTREAM_VERSION_MISMATCH")
    if wine.get("vulkanDriverVersion") != 49:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_VERSION_MISMATCH")

    required_true = (
        "abiEntryPointImplemented",
        "deviceLifecycleCallbacksImplemented",
        "presentQueueCallbackSourceIntegrated",
        "presentQueueTimelineSignalSourceIntegrated",
        "externalHandleFdMappingImplemented",
        "headlessDiagnosticSurfaceImplemented",
        "headlessDiagnosticPresentationSupportImplemented",
        "headlessDiagnosticExtensionMappingImplemented",
        "headlessPresentObserverImplemented",
    )
    required_false = (
        "deviceLifecycleCallbacksExecuted",
        "presentQueueCallbackExecuted",
        "presentQueueTimelineSignalExecuted",
        "visibleSurfaceCreateImplemented",
        "visiblePresentationSupportImplemented",
        "visibleSurfaceExtensionMappingImplemented",
        "swapchainCaptureImplemented",
        "hostVisiblePresentImplemented",
        "softwareTestExecuted",
        "integrationTestExecuted",
        "physicalTestExecuted",
    )
    for key in required_true:
        if wine.get(key) is not True:
            raise SystemExit(f"POCKETPC_VULKAN_ABI_POLICY_MISSING:architecture-{key}")
    for key in required_false:
        if wine.get(key) is not False:
            raise SystemExit(f"POCKETPC_VULKAN_ABI_POLICY_STATE_MISMATCH:{key}")

    guest = arch.get("guestGraphics") or {}
    for key in (
        "authenticatedSessionClientImplemented",
        "resourceOfferReceiverImplemented",
        "activeWineDeviceImportSourceIntegrated",
        "presentQueueTimelineSignalSourceIntegrated",
    ):
        if guest.get(key) is not True:
            raise SystemExit(f"POCKETPC_VULKAN_ABI_POLICY_MISSING:guest-{key}")
    for key in (
        "authenticatedRuntimeReceiveIntegrated",
        "activeWineDeviceImageImportIntegrated",
        "presentQueueTimelineSignalExecuted",
        "gpuQueueSynchronizationImplemented",
        "integrationTestExecuted",
        "physicalTestExecuted",
    ):
        if guest.get(key) is not False:
            raise SystemExit(f"POCKETPC_VULKAN_ABI_POLICY_STATE_MISMATCH:guest-{key}")

    gates = arch.get("gates") or {}
    for key in (
        "wineVulkanDeviceLifecycleCallbacksExecuted",
        "winePresentQueueCallbackExecuted",
        "presentQueueTimelineSignalExecuted",
        "authenticatedGuestGraphicsReceiveIntegrated",
        "guestVulkanImageImportIntegrated",
        "guestGraphicsGpuSynchronizationImplemented",
        "swapchainImageCaptureHookImplemented",
        "hostVisiblePresentImplemented",
        "visibleVulkanSurfaceBackendRunnable",
        "wineVisibleVulkanWsiImplemented",
        "d3d11PresentHostVisibleFrameExecuted",
        "robloxControlledAttemptExecuted",
        "robloxGameplayValidated",
    ):
        if gates.get(key) is not False:
            raise SystemExit(f"POCKETPC_VULKAN_ABI_POLICY_STATE_MISMATCH:{key}")

    print("POCKETPC_VULKAN_ABI_POLICY_OK")
    print("wine_vulkan_upstream_driver_version=47")
    print("wine_vulkan_pocketpc_driver_version=49")
    print("device_lifecycle_callbacks_source_integrated=true")
    print("present_queue_callback_source_integrated=true")
    print("present_queue_timeline_signal_source_integrated=true")
    print("present_queue_timeline_signal_executed=false")
    print("active_wine_device_import_source_integrated=true")
    print("headless_diagnostic_implemented=true")
    print("external_handle_fd_mapping=true")
    print("swapchain_capture=false")
    print("visible_wsi_implemented=false")
    print("host_visible_present=false")
    print("execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
