# Linux ARM runtime — Alpha 9

## Implemented foundations

- Runtime Manifest v2;
- ARM64 ABI gate;
- SHA-256 rootfs staging;
- safe TAR/TAR.GZ extraction;
- guest filesystem metadata;
- transactional LINKS_PREPARED;
- guest-aware entrypoint resolution;
- NOFOLLOW cleanup;
- bind/environment policy;
- PRoot argv planner;
- bounded one-shot process supervisor foundation;
- source provenance lock;
- artifact quarantine/ELF policy;
- Android packaging blocker detection;
- runtime artifact approval/attestation model.

## Full gate chain

~~~text
STAGED_VERIFIED
   ↓
INSTALLED_DATA
   ↓
LINKS_PREPARED
   ↓
ENTRYPOINT_RESOLVED
   ↓
SOURCE_VERIFIED
   ↓
ARTIFACT_ELF_REVIEWED
   ↓
ANDROID_PACKAGING_RESOLVED
   ↓
LICENSE_REVIEWED
   ↓
ARTIFACTS_LOCKED
   ↓
DEVICE_REVIEWED
   ↓
POLICY_DIGESTS_VERIFIED
   ↓
NATIVE_ARTIFACTS_ATTESTED
   ↓
prootReady
   ↓
[EXECUTOR STILL DISABLED]
~~~

## Current state

The project has source/policy implementations for the chain, but does not have real evidence for the later substrate gates.

Current embedded approval is false.

Therefore prootReady remains false by design.

## First shell claim

PocketPC may only claim a Linux /bin/sh execution after:

- an exact APK commit is known;
- prootReady is true on that exact device/APK;
- a separate executor gate is explicitly enabled;
- /bin/sh is launched through the reviewed substrate;
- output/exit code are captured;
- timeout/cleanup behavior is demonstrated;
- no root privilege is used.

Until then Linux execution remains **NOT IMPLEMENTED/NOT DEVICE TESTED**.


## Pinned rootfs candidate — Alpha 22

The repository now pins Ubuntu Base 24.04.4 ARM64 as the first reproducible guest userspace candidate in `third_party/rootfs/ubuntu-noble-arm64.json`.

`scripts/prepare-rootfs-package.py` downloads the exact upstream archive, verifies its pinned SHA-256, rejects unsafe, duplicate and unsupported TAR entries, enforces entry/extracted-byte limits, checks for a shell candidate and emits a PocketPC Runtime Manifest v2 without modifying the upstream archive.

`.github/workflows/rootfs-arm64-package.yml` can retain the verified archive, manifest and evidence as review artifacts. This does not mark the rootfs as device-tested and does not approve PRoot. A successful workflow proves only source/hash/structure preparation; Android staging/extraction, guest shell, Box64, Wine and Roblox remain separate gates.
