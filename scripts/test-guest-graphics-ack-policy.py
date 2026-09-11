#!/usr/bin/env python3
"""Lock PGA1 guest-import acknowledgement wiring without claiming execution."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
DRIVER = ROOT / "third_party/wine/pocketpc-driver"
RUNTIME = ROOT / "app/src/main/java/dev/pocketpc/core/runtime"
CPP = ROOT / "app/src/main/cpp/graphics_seqpacket_session.cpp"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"


def need(text: str, marker: str, label: str) -> None:
    if marker not in text:
        raise SystemExit(f"POCKETPC_GRAPHICS_ACK_POLICY_MISSING:{label}")


def absent(text: str, marker: str, label: str) -> None:
    if marker in text:
        raise SystemExit(f"POCKETPC_GRAPHICS_ACK_POLICY_FORBIDDEN:{label}")


def ordered(text: str, markers: list[tuple[str, str]]) -> None:
    previous = -1
    for marker, label in markers:
        position = text.find(marker)
        if position < 0:
            raise SystemExit(f"POCKETPC_GRAPHICS_ACK_POLICY_MISSING:{label}")
        if position <= previous:
            raise SystemExit(f"POCKETPC_GRAPHICS_ACK_POLICY_ORDER:{label}")
        previous = position


def main() -> int:
    header = (BRIDGE / "pocketpc_graphics_ack.h").read_text(encoding="utf-8")
    sender = (BRIDGE / "pocketpc_graphics_ack.c").read_text(encoding="utf-8")
    vulkan = (DRIVER / "vulkan.c").read_text(encoding="utf-8")
    makefile = (DRIVER / "Makefile.in").read_text(encoding="utf-8")
    host_cpp = CPP.read_text(encoding="utf-8")
    host_kotlin = (RUNTIME / "GraphicsSeqpacketSessionHost.kt").read_text(encoding="utf-8")
    orchestrator = (RUNTIME / "GuestGraphicsSessionOrchestrator.kt").read_text(encoding="utf-8")
    display = (RUNTIME / "RuntimeDisplayExecutionController.kt").read_text(encoding="utf-8")
    contract = (RUNTIME / "GuestGraphicsTransportContract.kt").read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")

    for marker, label in (
        ("#define PGA_MAGIC 0x31414750u", "pga-magic"),
        ("#define PGA_VERSION 1u", "pga-version"),
        ("#define PGA_PACKET_BYTES 48u", "pga-size"),
        ("PGA_STAGE_RESOURCE_OFFER_RECEIVED 1u", "stage-offer"),
        ("PGA_STAGE_IMAGE_IMPORTED 2u", "stage-image"),
        ("PGA_STAGE_SYNC_IMPORTED 3u", "stage-sync"),
        ("PGA_STAGE_READY 4u", "stage-ready"),
        ("PGA_STATUS_OK 0", "status-ok"),
    ):
        need(header, marker, label)

    for marker, label in (
        ("SOCK_SEQPACKET", "sender-seqpacket"),
        ("MSG_NOSIGNAL", "sender-nosignal"),
        ("ack->resource_id == 0u", "sender-resource-validation"),
        ("ack->generation == 0u", "sender-generation-validation"),
        ("ack->sequence == 0u", "sender-sequence-validation"),
    ):
        need(sender, marker, label)

    need(vulkan, '#include "pocketpc_graphics_ack.h"', "driver-ack-include")
    ordered(
        vulkan,
        [
            ("PGA_STAGE_RESOURCE_OFFER_RECEIVED", "driver-stage-offer"),
            ("PGA_STAGE_IMAGE_IMPORTED", "driver-stage-image"),
            ("PGA_STAGE_SYNC_IMPORTED", "driver-stage-sync"),
            ("PGA_STAGE_READY", "driver-stage-ready"),
        ],
    )
    need(vulkan, "pocketpc_graphics_ack_send", "driver-ack-send")
    need(vulkan, "PGA_STATUS_OK", "driver-success-status")

    for marker, label in (
        ("kPgaMagic = 0x31414750u", "host-pga-magic"),
        ("kPgaPacketBytes = 48", "host-pga-size"),
        ("kPgaReadyMask = 0x0f", "host-ready-mask"),
        ("CloseAncillaryFds", "host-closes-unexpected-rights"),
        ("if (had_control) return -25", "host-rejects-rights"),
        ("if (stage != expected_stage) return -27", "host-stage-order"),
        ("if (status != kPgaStatusOk) return -28", "host-status-check"),
        ("ack_resource_id != resource_id", "host-resource-identity"),
        ("ack_generation != generation", "host-generation-identity"),
        ("ack_sequence != sequence", "host-sequence-identity"),
        ("nativeAwaitImportAcks", "host-jni-ack-await"),
    ):
        need(host_cpp, marker, label)

    for marker, label in (
        ("IMPORT_ACK_READY_MASK = 0x0f", "kotlin-ready-mask"),
        ("nativeAwaitImportAcks", "kotlin-native-await"),
        ("awaitImportAcknowledgements", "kotlin-await-wrapper"),
        ("it == IMPORT_ACK_READY_MASK", "kotlin-exact-mask"),
        ("It does not prove GPU queue synchronization", "kotlin-non-promotion-comment"),
    ):
        need(host_kotlin, marker, label)

    for marker, label in (
        ("awaitGuestImportConfirmation", "orchestrator-await"),
        ("CONFIRM_GUEST_IMPORT", "orchestrator-import-transition"),
        ("offer.ownership.sequence + 1L", "orchestrator-next-sequence"),
        ("GUEST_IMPORT_CONFIRMED", "orchestrator-state"),
        ("guestImportConfirmed", "orchestrator-proof-field"),
    ):
        need(orchestrator, marker, label)

    for marker, label in (
        ("graphicsGuestImportConfirmed", "display-result-field"),
        ("awaitGuestImportConfirmation", "display-await-integration"),
        ("GUEST_GRAPHICS_IMPORT_NOT_COMPLETED_BEFORE_PROCESS_EXIT", "display-exit-blocker"),
    ):
        need(display, marker, label)

    for marker, label in (
        ('"pocketpc_graphics_ack.h"', "overlay-ack-header"),
        ('"pocketpc_graphics_ack.c"', "overlay-ack-source"),
        ('"graphicsImportAcknowledgementProtocol": "PGA1"', "overlay-ack-evidence"),
        ('"guestImportAcknowledgementSourceIntegrated": True', "overlay-source-integrated"),
    ):
        need(preparer, marker, label)

    need(makefile, "\tpocketpc_graphics_ack.c \\", "makefile-ack-source")

    for marker, label in (
        ("guestImportAcknowledgementProtocolImplemented = true", "contract-ack-protocol"),
        ("guestImportAcknowledgementHostValidationImplemented = true", "contract-ack-validation"),
        ("guestImportOwnershipPromotionImplemented = true", "contract-ownership-promotion"),
        ("guestReceiveImplemented = false", "contract-observed-receive-false"),
        ("guestImportImplemented = false", "contract-observed-import-false"),
        ("synchronizationImplemented = false", "contract-sync-false"),
        ("integrationTestExecuted = false", "contract-integration-not-executed"),
        ("physicalTestExecuted = false", "contract-physical-not-executed"),
    ):
        need(contract, marker, label)

    for marker, label in (
        ("graphicsGuestImportConfirmed = true", "hardcoded-runtime-import-pass"),
        ("guestImportImplemented = true", "observed-import-promoted-without-execution"),
        ("synchronizationImplemented = true", "gpu-sync-promoted-without-execution"),
    ):
        absent(contract, marker, label)

    print("POCKETPC_GRAPHICS_ACK_POLICY_OK")
    print("protocol=PGA1")
    print("ordered_stages=1,2,3,4")
    print("host_identity_validation=true")
    print("ownership_promotion_source_integrated=true")
    print("guest_import_execution_evidence=false")
    print("gpu_synchronization=false")
    print("visible_present=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
