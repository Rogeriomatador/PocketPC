# PocketPC PRoot supply-chain lock

Status: SOURCE_METADATA_LOCKED / NO BINARIES BUNDLED

This directory pins the upstream metadata PocketPC intends to audit before any PRoot execution substrate is allowed into an APK.

## Authority

The lock is based on Termux package recipes at:

- repository: termux/termux-packages
- commit: 32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68
- timestamp: 2026-09-06T09:56:57Z

Pinned components:

| Component | Version | License | Source SHA-256 |
|---|---|---|---|
| PRoot | 5.1.107.92 | GPL-2.0 | 29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135 |
| libandroid-shmem | 0.7 | BSD 3-Clause | 1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867 |
| libtalloc | 2.4.3 | GPL-3.0 | dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd |

PRoot tag v5.1.107.92 resolves to commit 7266fb3e8516535682f5a9c8f3a7e70f6506eddb.
libandroid-shmem tag v0.7 resolves to commit 7f0bd7e25dbdd146265aff7c6a890029e374622d.

## Evidence boundary

The hashes above are upstream recipe metadata. This session inspected the recipes and Git tags, but did not independently download the source archives and recalculate those archive hashes.

scripts/audit-proot-sources.py performs that independent download/hash check in an environment with network access.

Do not label the supply chain VERIFIED until that audit passes.

## PocketPC packaging adaptation — DESIGN

Upstream PRoot installs its unbundled 64-bit loader as loader. PRoot supports overriding that location with PROOT_LOADER.

PocketPC proposes:

- PRoot executable packaged as libproot.so
- ARM64 loader packaged as libproot_loader.so
- PROOT_LOADER points to nativeLibraryDir/libproot_loader.so
- no loader32 in the first ARM64-only gate

No third-party binary is currently bundled.

## License gate

Issue #4 must record source/build provenance, artifact hashes, GPL/BSD obligations and device evidence before bundling is permitted.
