#!/usr/bin/env python3
"""Static lock for PocketPC Android runtime selection/wiring of experimental v52.

This script does not compile or execute Android, Wine, Vulkan, JNI, or Roblox.
It only prevents source regressions that could silently select v52 for a v51
artifact, let an arbitrary launch environment enable v52, or promote model
frame delivery to physical-visible evidence.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeGraphicsPresentPolicy.kt"
CONTROLLER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayExecutionController.kt"
RUNNER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayContinuousPresentV52Runner.kt"
PORT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/VulkanContinuousPresentBrokerPort.kt"
BRIDGE = ROOT / "app/src/main/cpp/vulkan_continuous_present_broker_port.cpp"
CMAKE = ROOT / "app/src/main/cpp/CMakeLists.txt"
OFFICIAL_WINE_WORKFLOW = ROOT / ".github/workflows/wine-x86_64-build.yml"


def fail(message: str) -> None:
    raise SystemExit(f"FAIL runtime v52 present selection policy: {message}")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        fail(f"{label} missing {needle!r}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        fail(f"{label} contains forbidden {needle!r}")


def require_order(text: str, earlier: str, later: str, label: str) -> None:
    first = text.find(earlier)
    second = text.find(later)
    if first < 0 or second < 0:
        fail(f"{label} order token missing: {earlier!r} / {later!r}")
    if first >= second:
        fail(f"{label} must keep {earlier!r} before {later!r}")


def main() -> None:
    policy = POLICY.read_text(encoding="utf-8")
    controller = CONTROLLER.read_text(encoding="utf-8")
    runner = RUNNER.read_text(encoding="utf-8")
    port = PORT.read_text(encoding="utf-8")
    bridge = BRIDGE.read_text(encoding="utf-8")
    cmake = CMAKE.read_text(encoding="utf-8")
    official_workflow = OFFICIAL_WINE_WORKFLOW.read_text(encoding="utf-8")

    # Selection remains v51 unless an explicit, verified v52 request is made.
    require(policy, "if (declaration?.requestContinuousPresentV52 != true)", "selection default")
    require(policy, "RuntimeGraphicsPresentMode.V51_ONE_SHOT", "v51 default")
    require(policy, "!declaration.verifiedArtifactMetadata", "verified metadata gate")
    require(policy, "declaration.runtimeIdentity != expectedRuntimeIdentity", "runtime identity gate")
    require(policy, "declaration.wineVulkanAbi != WINE_VULKAN_ABI_V52", "ABI gate")
    require(policy, "CAPABILITY_CONTINUOUS_PRESENT_V52 !in declaration.capabilities", "capability gate")
    require(policy, "const val WINE_VULKAN_ABI_V52 = 52", "v52 ABI")
    require(policy, '"pocketpc.vulkan.continuous-present.v52"', "v52 capability")
    require(policy, '"POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52"', "guest environment gate")
    require(policy, 'mapOf(ENV_CONTINUOUS_PRESENT_V52 to "1")', "selected v52 environment")

    # The exact runtime identity is bound before selection, and arbitrary base
    # environment input is stripped before policy-owned environment injection.
    require(controller, "RuntimeExecutionIdentity.of(runtime, tools, layers)", "runtime identity binding")
    require(controller, "RuntimeGraphicsPresentPolicy.select(", "runtime selection")
    require(controller, "expectedRuntimeIdentity = identity", "selection identity")
    require(controller, "remove(RuntimeGraphicsPresentPolicy.ENV_CONTINUOUS_PRESENT_V52)", "environment stripping")
    require(controller, "putAll(RuntimeGraphicsPresentPolicy.launchEnvironment(graphicsSelection))", "policy environment injection")
    require_order(
        controller,
        "remove(RuntimeGraphicsPresentPolicy.ENV_CONTINUOUS_PRESENT_V52)",
        "putAll(RuntimeGraphicsPresentPolicy.launchEnvironment(graphicsSelection))",
        "environment ownership",
    )

    # v52 can only start after guest import confirmation, and it branches away
    # before all v51 one-shot queue/copy/readback stages.
    require(controller, "if (graphicsSelection.continuousV52Selected)", "v52 branch")
    require(controller, "RuntimeDisplayContinuousPresentV52Runner.runUntilCancelled(", "v52 runner dispatch")
    require_order(
        controller,
        "guestImportConfirmed = true,",
        "if (graphicsSelection.continuousV52Selected)",
        "guest import before v52",
    )
    require_order(
        controller,
        "if (graphicsSelection.continuousV52Selected)",
        "graphics.awaitGpuQueueSignalProbe(",
        "v52 before v51 queue stage",
    )
    require_order(
        controller,
        "if (graphicsSelection.continuousV52Selected)",
        "graphics.awaitPresentCopyCompletion(",
        "v52 before v51 present-copy stage",
    )
    require_order(
        controller,
        "if (graphicsSelection.continuousV52Selected)",
        "graphics.consumePresentCopyOnAndroidHost(",
        "v52 before v51 host readback",
    )
    require(controller, "!graphicsSelection.continuousV52Selected", "v52 process-exit distinction")

    # The continuous runner retains ownership of the exact imported identity,
    # retries pending host-consumed signals via the coordinator, and always
    # closes the native port at the lifecycle boundary.
    require(runner, "require(selection.continuousV52Selected)", "runner selection gate")
    require(runner, "require(importedOffer.guestImportConfirmed)", "runner import gate")
    require(runner, "VulkanContinuousPresentBrokerPort.open(", "runner broker port")
    require(runner, "VulkanContinuousPresentHostCoordinator(", "runner coordinator")
    require(runner, "MODEL_DELIVERED_SIGNAL_PENDING", "pending even recovery")
    require(runner, "READBACK_POISONED_GENERATION", "generation poison handling")
    require(runner, "finally {", "runner lifecycle close")
    require(runner, "port.close()", "runner port close")
    require(runner, "hostVisibleFrameValidated: Boolean = false", "physical fail-closed")
    require(runner, "robloxValidated: Boolean = false", "Roblox fail-closed")

    # Broker-backed host open validates the immutable PVI1 identity and then
    # delegates to the persistent native v52 session. Source presence is not
    # treated as execution evidence.
    require(port, "offer.ownership.sequence", "immutable offer sequence")
    require(port, "visible_frame", "native visible-frame field validation")
    require(port, 'fields["visible_frame"] == "0"', "visible-frame fail-closed")
    require(bridge, "GetU64Le(p + 8) == rid", "native resource identity")
    require(bridge, "GetU64Le(p + 16) == gen", "native generation identity")
    require(bridge, "GetU64Le(p + 24) == offer", "native offer identity")
    require(bridge, "VulkanContinuousPresentNativeSession_nativeOpen", "persistent host delegation")
    require(cmake, "vulkan_continuous_present_broker_port.cpp", "native build source inclusion")

    # Official Wine remains v51. The experimental Android source path alone is
    # never allowed to auto-promote Wine v52 into the official artifact.
    require(official_workflow, "python3 scripts/build-wine-x86_64-v51.py", "official v51 builder")
    forbid(official_workflow, "build-wine-x86_64-v52.py", "official Wine workflow")
    forbid(official_workflow, "prepare-wine-pocketpc-driver-v52.py", "official Wine workflow")
    forbid(official_workflow, "POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1", "official Wine workflow")

    print("PASS static runtime v52 present selection policy")
    print("CLASSIFICATION=IMPLEMENTED_SOURCE_NOT_EXECUTED")
    print("DEFAULT_PRESENT_PATH=v51")
    print("V52_REQUIRES_VERIFIED_ARTIFACT_METADATA=1")
    print("V52_RUNTIME_EXECUTED=0")
    print("PHYSICAL=NOT_EXECUTED")
    print("ROBLOX=NOT_EXECUTED")


if __name__ == "__main__":
    main()
