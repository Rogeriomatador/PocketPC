#!/usr/bin/env python3
"""Prepare a PocketPC Runtime Manifest v2 from a pinned upstream rootfs."""
from __future__ import annotations
import argparse, hashlib, json, shutil, tarfile, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = ROOT / "third_party/rootfs/ubuntu-noble-arm64.json"

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(1024 * 1024): h.update(block)
    return h.hexdigest()

def normalize_member(name: str) -> str | None:
    raw = name.removeprefix("./")
    if not raw or raw.startswith("/") or "\x00" in raw or "\\" in raw: return None
    parts = []
    for part in raw.split("/"):
        if part in ("", "."): continue
        if part == "..": return None
        parts.append(part)
    return "/".join(parts) or None

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    p.add_argument("--work", type=Path, required=True)
    args = p.parse_args()
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    work = args.work.resolve(); work.mkdir(parents=True, exist_ok=False)
    archive = work / "rootfs.tar.gz"; partial = work / "rootfs.tar.gz.part"
    with urllib.request.urlopen(lock["sourceUrl"], timeout=120) as response, partial.open("wb") as out:
        shutil.copyfileobj(response, out)
    actual = sha256(partial)
    if actual != lock["sourceSha256"]:
        raise SystemExit(f"ROOTFS_SHA256_MISMATCH expected={lock['sourceSha256']} actual={actual}")
    partial.replace(archive)
    entry_count = 0; logical_bytes = 0; names = set(); has_shell = False
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle:
            entry_count += 1
            if entry_count > int(lock["entryLimit"]): raise SystemExit("ROOTFS_ENTRY_LIMIT_EXCEEDED")
            normalized = normalize_member(member.name)
            if normalized is None: raise SystemExit("ROOTFS_UNSAFE_PATH:" + member.name)
            if normalized in names: raise SystemExit("ROOTFS_DUPLICATE_ENTRY:" + normalized)
            names.add(normalized)
            if member.isreg():
                logical_bytes += member.size
                if logical_bytes > int(lock["extractedBytesLimit"]): raise SystemExit("ROOTFS_EXTRACTED_LIMIT_EXCEEDED")
            if normalized in {"bin/sh", "usr/bin/sh"}: has_shell = True
            if not (member.isreg() or member.isdir() or member.issym() or member.islnk()):
                raise SystemExit("ROOTFS_UNSUPPORTED_SPECIAL_ENTRY:" + normalized)
    if not has_shell: raise SystemExit("ROOTFS_SHELL_CANDIDATE_MISSING")
    manifest = {
        "schemaVersion": 2, "id": lock["id"], "name": lock["name"], "version": lock["version"],
        "architecture": lock["architecture"], "rootfsSha256": actual, "rootfsBytes": archive.stat().st_size,
        "entrypoint": lock["entrypoint"], "license": lock["license"], "archiveFormat": lock["archiveFormat"],
        "extractedBytesLimit": lock["extractedBytesLimit"], "entryLimit": lock["entryLimit"]
    }
    (work / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    evidence = {
        "status": "SOURCE_VERIFIED_STRUCTURE_AUDITED_NOT_DEVICE_TESTED",
        "sourceUrl": lock["sourceUrl"], "sha256": actual, "archiveBytes": archive.stat().st_size,
        "tarEntries": entry_count, "logicalRegularFileBytes": logical_bytes, "shellCandidatePresent": has_shell,
        "notExecuted": ["PocketPC staging","PocketPC extraction","PRoot guest execution","Box64","Wine","Roblox"]
    }
    (work / "rootfs-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print("ROOTFS_PACKAGE_PREPARED")
    return 0

if __name__ == "__main__": raise SystemExit(main())
