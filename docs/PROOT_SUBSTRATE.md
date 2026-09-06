# PRoot execution substrate — Alpha 9

Status: **SOURCE LOCKED / QUARANTINE DEFINED / RUNTIME APPROVAL DENIED / EXECUTOR DISABLED**

## Supply-chain layers

PocketPC deliberately separates:

1. source metadata lock;
2. independent source archive audit;
3. quarantine build;
4. ELF/dependency audit;
5. Android packaging resolution;
6. license review;
7. final artifact lock;
8. physical-device review;
9. runtime artifact attestation;
10. Linux executor enablement.

Passing one layer never implies the next.

## Raw substrate candidates

Expected raw roles:

- proot executable;
- ARM64 loader;
- libandroid-shmem;
- libtalloc.

## Proposed APK aliases

Fixed candidates:

- libproot.so
- libproot_loader.so
- libandroid-shmem.so

talloc alias remains unresolved until the real build/link contract is audited.

All eventual APK aliases must end in .so.

## PROOT_LOADER

The planned environment remains:

~~~text
PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so
~~~

This remains untested on a physical PocketPC build.

## Runtime attestation

Alpha 9 removes file-presence trust.

prootReady requires:

- PocketPC native host present;
- approval manifest approved;
- source lock asset hash match;
- artifact contract asset hash match;
- final artifact lock asset hash match;
- exact native file bytes/SHA-256;
- execute permission where required;
- no unexpected sensitive substrate file.

Current approval=false, so prootReady remains false.

## Android packaging

The artifact audit now reports a dedicated blocker when talloc's filename/SONAME/DT_NEEDED is versioned in a way that cannot simply map to lib/<abi>/lib<name>.so.

This blocker must be resolved in the actual build rather than hidden through renaming.

## Executor

Even SUBSTRATE_ARTIFACTS_ATTESTED would not start Linux automatically.

The separate executor path remains disabled and will require its own reviewed transition.
