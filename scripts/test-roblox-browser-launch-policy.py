#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
DEEP_LINK = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RobloxPlayerDeepLink.kt"
COORDINATOR = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RobloxBrowserLaunchCoordinator.kt"
READINESS = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RobloxLaunchReadiness.kt"
PLANNER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RobloxInstalledLaunchPlan.kt"


def require(failures: list[str], label: str, text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    deep_link = DEEP_LINK.read_text(encoding="utf-8")
    coordinator = COORDINATOR.read_text(encoding="utf-8")
    readiness = READINESS.read_text(encoding="utf-8")
    planner = PLANNER.read_text(encoding="utf-8")

    require(
        failures,
        "deep-link parser",
        deep_link,
        (
            'private const val SCHEME = "roblox-player:"',
            "private const val MAX_LENGTH = 8_192",
            "value != value.trim()",
            "character.code < 0x20",
            "character.code == 0x7f",
            "PocketBrowserExternalRouteKind.ROBLOX_PLAYER",
            "BLOCKED_EXTERNAL",
        ),
    )
    require(
        failures,
        "browser launch coordinator",
        coordinator,
        (
            "object RobloxBrowserLaunchCoordinator",
            "RobloxPlayerDeepLink.parse(rawUri)",
            "RobloxBrowserLaunchDecisionKind.RUNTIME_BLOCKED",
            "readiness.controlledAttemptReady",
            "mayBuildControlledAttempt",
        ),
    )
    require(
        failures,
        "Roblox readiness guest gate",
        readiness,
        (
            "ROBLOX_GUEST_GRAPHICS_TRANSPORT_NOT_READY",
            "wsiFoundation?.guestGraphicsTransportReady != true",
            "PocketPcVulkanWsiContract.implemented",
        ),
    )
    require(
        failures,
        "installed Roblox launch planner",
        planner,
        (
            "deepLink: RobloxPlayerDeepLink? = null",
            "val windowsArguments =",
            "listOf(it.raw)",
            "windowsArgs = windowsArguments",
            "shellArguments(wine.argv)",
        ),
    )

    # Deep links must remain opaque argv data. Never interpolate them directly
    # into a shell command or hand them to Android's implicit intent resolver.
    for forbidden in (
        "Intent.ACTION_VIEW",
        "Runtime.getRuntime().exec",
        "ProcessBuilder(it.raw)",
        'sh -c ${deepLink',
        'sh -c $deepLink',
    ):
        if forbidden in coordinator or forbidden in planner:
            failures.append(
                "Roblox browser/runtime boundary contains forbidden execution path: "
                + forbidden
            )

    if failures:
        print("ROBLOX_BROWSER_LAUNCH_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("ROBLOX_BROWSER_LAUNCH_POLICY_OK")
    print("deep_link_scheme=roblox-player")
    print("browser_decision_boundary_implemented=true")
    print("deep_link_shell_interpolation=false")
    print("android_implicit_launch=false")
    print("controlled_attempt_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
