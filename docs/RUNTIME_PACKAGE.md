# Runtime package format — schema v1

Status: staging/verification **IMPLEMENTED**. Rootfs execution **NOT IMPLEMENTED**.

## Manifest

A manifest is UTF-8 JSON, maximum 128 KiB:

~~~json
{
  "schemaVersion": 1,
  "id": "debian.bookworm",
  "name": "Debian Bookworm",
  "version": "12.9-1",
  "architecture": "aarch64",
  "rootfsSha256": "<64 lowercase/uppercase hex characters>",
  "rootfsBytes": 123456789,
  "entrypoint": "/bin/sh",
  "license": "Upstream redistribution/license description"
}
~~~

## Validation

Alpha 4 requires:

- schema 1;
- safe ID;
- safe version segment with no slash path traversal;
- architecture exactly aarch64;
- Android device exposes arm64-v8a;
- 64-character SHA-256;
- positive rootfs size up to 16 GiB;
- normalized absolute Linux entrypoint with no .. segment;
- non-empty license metadata.

## Staging transaction

1. user selects manifest;
2. user selects rootfs archive/data file;
3. PocketPC copies into a temporary app-private staging directory;
4. byte count is checked while streaming;
5. SHA-256 is checked;
6. manifest + VERIFIED marker are written;
7. only then is the verified staging promoted;
8. failures delete temporary data;
9. the rootfs is **not executed**.\n10. PocketPC keeps a free-space reserve before copying.\n11. a verified staging can be re-audited by recomputing SHA-256 and byte count.\n12. interrupted temporary staging directories are cleaned on recovery; recoverable verified backups are restored when possible.

The rootfs archive format is intentionally not fixed yet. Safe extraction rules will be defined before extraction is implemented.

## Why code and rootfs are separated

For target SDK 29+, Android blocks direct execution from writable app-home data under the W^X policy. PocketPC targets API 37. Runtime host/loader code therefore needs an Android-compliant packaged executable path, while the rootfs remains verified data.

Relevant Android behavior documentation:
https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
