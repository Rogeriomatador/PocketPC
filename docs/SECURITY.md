# Security model — Alpha 6

## Existing archive protections

- SHA-256 staging verification;
- bounded TAR/TAR.GZ extraction;
- path traversal rejection;
- no guest symlink/hardlink materialization;
- transactional install;
- size/entry/path/header limits.

## Execution preparation protections

- no shell-string PRoot invocation;
- host binds limited to canonical app-owned roots;
- normalized absolute guest paths;
- duplicate guest mounts rejected;
- user binds cannot override reserved system locations;
- colon/exclamation guest syntax rejected;
- read-only binds fail closed until their semantics are implemented;
- minimal whitelisted environment;
- explicit PROOT_LOADER alias path;
- inherited process environment cleared;
- bounded captured output;
- command timeout and force-kill fallback;
- only one supervised process at a time;
- executor remains disabled.

## Supply-chain protections

- PRoot/libandroid-shmem/libtalloc source metadata locked to an exact Termux recipe commit;
- exact upstream PRoot and libandroid-shmem Git tag commits recorded;
- source archive SHA-256 values pinned;
- independent source-audit script defined;
- separate source-audit workflow defined;
- Android CI rejects unreviewed PRoot-named binaries.

Pinned metadata is not labelled independently verified until the source-audit script successfully recalculates the archive hashes.

## Known open risks

- PRoot child/process-tree termination on Android is not device validated;
- PTY is not implemented;
- guest links are metadata only;
- /proc, /sys and /dev policy is not finalized;
- user SAF storage has no direct bind bridge;
- PRoot produced binaries do not yet have PocketPC build hashes;
- host dynamic-linker behavior for the proposed aliases is not device validated.

No runtime may be labelled EXECUTABLE_LINUX until those gates are addressed with evidence.
