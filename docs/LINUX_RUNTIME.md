# Linux ARM runtime — Alpha 7

## Implemented foundations

- Runtime Manifest v2;
- ARM64 ABI gate;
- archive SHA-256 staging;
- safe TAR/TAR.GZ extraction;
- rootfs metadata;
- transactional INSTALLED_DATA;
- guest symlink/hardlink semantics source;
- transactional LINKS_PREPARED;
- NOFOLLOW cleanup;
- guest entrypoint resolver;
- bind/environment policy;
- PRoot argv planner;
- one-shot process supervisor foundation;
- substrate presence probe;
- PRoot source provenance lock.

## Runtime gates

~~~text
STAGED_VERIFIED
   ↓
INSTALLED_DATA
   ↓
LINKS_PREPARED
   ↓
ENTRYPOINT_RESOLVED
   ↓
SUBSTRATE_REVIEWED
   ↓
EXECUTOR_ENABLED
   ↓
EXECUTABLE_LINUX
~~~

Current project stops before SUBSTRATE_REVIEWED/EXECUTOR_ENABLED.

## Link behavior

The rootfs is extracted link-free. Links are materialized later from validated metadata.

This allows common Linux layouts such as bin -> usr/bin while preventing extraction from following that link.

## First Linux shell gate

A /bin/sh claim requires:

1. exact APK commit;
2. rootfs archive integrity;
3. LINKS_PREPARED verification;
4. guest entrypoint resolution;
5. independently audited PRoot sources;
6. reviewed produced PRoot artifacts;
7. packaged loader contract verified;
8. process supervisor wired to PRoot;
9. physical Android command log;
10. deterministic stop/cleanup;
11. no root or unrestricted storage permission.
