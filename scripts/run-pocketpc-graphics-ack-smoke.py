#!/usr/bin/env python3
"""Compile and execute the standalone PGA1 acknowledgement sender smoke.

This is a SOFTWARE TEST of the packet sender only. It does not execute Wine,
Box64, Android JNI, Vulkan import, GPU synchronization, Present, or Roblox.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import textwrap

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
ACK_C = BRIDGE / "pocketpc_graphics_ack.c"
ACK_H = BRIDGE / "pocketpc_graphics_ack.h"

HARNESS = r'''
#define _GNU_SOURCE
#include "pocketpc_graphics_ack.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static uint16_t g16(const unsigned char *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8u);
}

static uint32_t g32(const unsigned char *p)
{
    return (uint32_t)p[0] |
        ((uint32_t)p[1] << 8u) |
        ((uint32_t)p[2] << 16u) |
        ((uint32_t)p[3] << 24u);
}

static uint64_t g64(const unsigned char *p)
{
    uint64_t value = 0;
    unsigned int i;
    for (i = 0; i < 8u; ++i) value |= ((uint64_t)p[i]) << (i * 8u);
    return value;
}

static int receive_and_validate(
    int fd,
    uint16_t stage,
    uint64_t resource_id,
    uint64_t generation,
    uint64_t sequence,
    uint32_t detail)
{
    unsigned char packet[PGA_PACKET_BYTES];
    ssize_t received = recv(fd, packet, sizeof(packet), MSG_TRUNC);
    if (received != (ssize_t)sizeof(packet)) return 20;
    if (g32(packet + 0) != PGA_MAGIC) return 21;
    if (g16(packet + 4) != PGA_VERSION) return 22;
    if (g16(packet + 6) != stage) return 23;
    if ((int32_t)g32(packet + 8) != PGA_STATUS_OK) return 24;
    if (g32(packet + 12) != 0u) return 25;
    if (g64(packet + 16) != resource_id) return 26;
    if (g64(packet + 24) != generation) return 27;
    if (g64(packet + 32) != sequence) return 28;
    if (g32(packet + 40) != detail) return 29;
    if (g32(packet + 44) != 0u) return 30;
    return 0;
}

int main(void)
{
    int sockets[2] = {-1, -1};
    struct pocketpc_graphics_ack ack;
    uint16_t stage;
    int result;

    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets) != 0)
        return 10;

    memset(&ack, 0, sizeof(ack));
    ack.status = PGA_STATUS_OK;
    ack.resource_id = 0x1122334455667788ull;
    ack.generation = 9u;
    ack.sequence = 1u;

    for (stage = PGA_STAGE_RESOURCE_OFFER_RECEIVED; stage <= PGA_STAGE_READY; ++stage)
    {
        ack.stage = stage;
        ack.detail = 0x1000u + stage;
        result = pocketpc_graphics_ack_send(sockets[0], &ack);
        if (result != 0) return 40 + stage;
        result = receive_and_validate(
            sockets[1],
            stage,
            ack.resource_id,
            ack.generation,
            ack.sequence,
            ack.detail);
        if (result != 0) return result;
    }

    ack.stage = 0u;
    if (pocketpc_graphics_ack_send(sockets[0], &ack) >= 0) return 50;
    ack.stage = PGA_STAGE_READY;
    ack.resource_id = 0u;
    if (pocketpc_graphics_ack_send(sockets[0], &ack) >= 0) return 51;

    close(sockets[0]);
    close(sockets[1]);
    puts("POCKETPC_PGA1_SMOKE_OK");
    return 0;
}
'''


def run(command: list[str], *, cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    compiler = shutil.which("cc") or shutil.which("gcc")
    if not compiler:
        raise SystemExit("POCKETPC_PGA1_SMOKE_COMPILER_MISSING")
    if not ACK_C.is_file() or not ACK_H.is_file():
        raise SystemExit("POCKETPC_PGA1_SMOKE_SOURCE_MISSING")

    work = args.work.resolve()
    evidence = args.evidence.resolve()
    work.mkdir(parents=True, exist_ok=True)
    evidence.parent.mkdir(parents=True, exist_ok=True)

    harness = work / "pga1_smoke.c"
    binary = work / "pga1_smoke"
    harness.write_text(textwrap.dedent(HARNESS), encoding="utf-8")

    compile_result = run(
        [
            compiler,
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-I",
            str(BRIDGE),
            str(harness),
            str(ACK_C),
            "-o",
            str(binary),
        ],
        cwd=work,
    )

    execution_result = None
    if compile_result.returncode == 0:
        execution_result = run([str(binary)], cwd=work)

    passed = bool(
        compile_result.returncode == 0
        and execution_result is not None
        and execution_result.returncode == 0
        and "POCKETPC_PGA1_SMOKE_OK" in execution_result.stdout
    )

    payload = {
        "schemaVersion": 1,
        "classification": "SOFTWARE_TEST" if passed else "SOFTWARE_TEST_FAILED",
        "scope": "PGA1 standalone sender packet serialization and validation harness",
        "protocol": "PGA1",
        "protocolVersion": 1,
        "packetBytes": 48,
        "orderedStagesExercised": [1, 2, 3, 4],
        "invalidStageRejected": passed,
        "zeroResourceIdentityRejected": passed,
        "compile": {
            "executed": True,
            "returnCode": compile_result.returncode,
            "output": compile_result.stdout[-12000:],
        },
        "execution": {
            "executed": execution_result is not None,
            "returnCode": execution_result.returncode if execution_result else None,
            "output": execution_result.stdout[-12000:] if execution_result else "",
        },
        "notExecuted": [
            "Android JNI PGA1 receiver",
            "Box64",
            "Wine",
            "active Wine VkDevice import",
            "GPU queue synchronization",
            "DXVK Present",
            "host-visible frame",
            "Roblox",
        ],
    }
    evidence.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    if not passed:
        print("POCKETPC_PGA1_SMOKE_FAILED")
        print(compile_result.stdout)
        if execution_result is not None:
            print(execution_result.stdout)
        return 1

    print("POCKETPC_PGA1_SMOKE_OK")
    print(f"evidence={evidence}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
