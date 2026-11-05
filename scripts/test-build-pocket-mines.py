#!/usr/bin/env python3
"""Builder policy tests. These do not execute the Windows game."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("build-pocket-mines.py")
spec = importlib.util.spec_from_file_location("pocket_mines_builder", SCRIPT)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fake_pe(machine: int = 0x8664, magic: int = 0x20b, subsystem: int = 2) -> bytes:
    blob = bytearray(512)
    blob[:2] = b"MZ"
    struct.pack_into("<I", blob, 0x3c, 0x80)
    blob[0x80:0x84] = b"PE\x00\x00"
    struct.pack_into("<H", blob, 0x84, machine)
    struct.pack_into("<H", blob, 0x80 + 24, magic)
    struct.pack_into("<H", blob, 0x80 + 24 + 68, subsystem)
    return bytes(blob)


class PocketMinesBuildTest(unittest.TestCase):
    def test_accepts_win64_gui(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp, "game.exe")
            path.write_bytes(fake_pe())
            self.assertEqual(module.inspect_pe64_gui(path), {
                "machine": 0x8664, "optionalMagic": 0x20b, "subsystem": 2
            })

    def test_rejects_wrong_architecture(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp, "game.exe")
            for machine in (0x14c, 0xaa64):
                path.write_bytes(fake_pe(machine=machine))
                with self.assertRaises(ValueError):
                    module.inspect_pe64_gui(path)

    def test_rejects_console_and_wrong_magic(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp, "game.exe")
            for data in (fake_pe(subsystem=3), fake_pe(magic=0x10b)):
                path.write_bytes(data)
                with self.assertRaises(ValueError):
                    module.inspect_pe64_gui(path)

    def test_rejects_bad_headers(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp, "game.exe")
            for data in (b"MZ", b"NO", bytes(512), b"MZ" + bytes(254)):
                path.write_bytes(data)
                with self.assertRaises(ValueError):
                    module.inspect_pe64_gui(path)

    def test_output_must_be_outside_checkout(self) -> None:
        with self.assertRaises(ValueError):
            module.build(module.ROOT)
        with self.assertRaises(ValueError):
            module.build(module.ROOT / "build/pocket-mines")


if __name__ == "__main__":
    unittest.main()
