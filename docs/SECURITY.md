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
- minimal whitelisted environment;
- inherited process environment cleared;
- bounded captured output;
- command timeout and force-kill fallback;
- only one supervised process at a time;
- executor remains disabled.

## Known open risks

- PRoot child/process-tree termination on Android is not device validated;
- PTY is not implemented;
- guest links are metadata only;
- /proc, /sys and /dev policy is not finalized;
- user SAF storage has no direct bind bridge;
- PRoot binaries have not completed provenance/license review.

No runtime may be labelled EXECUTABLE_LINUX until those gates are addressed with evidence.
