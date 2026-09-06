# Security model — Alpha 7

## Archive boundary

- SHA-256 verified archive staging;
- bounded TAR/TAR.GZ extraction;
- absolute/dot-dot path rejection;
- duplicate/special entry rejection;
- no links during extraction;
- size/entry/path/header limits.

## Guest link boundary

- links are metadata during extraction;
- link paths are validated before creation;
- symlink targets are resolved with guest semantics for validation;
- hardlink chains are cycle checked;
- hardlinks require a regular-file base target;
- metadata SHA-256 is bound to LINKS_PREPARED;
- interrupted link preparation is recovered before retry;
- verification checks symlink target text and hardlink identity;
- launch gate refuses invalid/missing prepared links.

## Cleanup boundary

Installed rootfs cleanup and rollback use SafeTreeOps.deleteNoFollow.

No recursive cleanup is allowed to follow a guest symlink.

## Execution boundary

- executor remains disabled;
- no shell-string command construction;
- host bind allowlist;
- read-only binds fail closed;
- minimal environment;
- explicit PROOT_LOADER alias;
- bounded process output and timeout.

## Supply chain

- PRoot source metadata lock is pinned;
- independent source audit workflow is defined;
- no PRoot binaries are bundled;
- artifact/build/license audit remains Issue #4.

## Remaining high-priority risks

- physical Android link behavior is untested;
- PRoot execution is untested;
- process-tree termination is untested;
- PTY is absent;
- proc/dev/sys policy is unresolved;
- absolute guest symlink behavior under the final PRoot build needs device evidence.
