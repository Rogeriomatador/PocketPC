# Execution model — Alpha 7

Status: **execution foundation implemented / executor disabled**

## Pre-execution filesystem gates

Before PRoot can be considered:

1. archive must be STAGED_VERIFIED;
2. rootfs must be INSTALLED_DATA;
3. guest links must be LINKS_PREPARED when required;
4. link marker/hash must verify;
5. manifest entrypoint must resolve through guest symlink semantics to a regular file.

## Guest-aware entrypoint resolution

PocketPC does not use Android host canonical symlink resolution for Linux entrypoints.

Example:

~~~text
bin -> usr/bin
/bin/sh -> /usr/bin/sh
~~~

RootfsGuestResolver resolves the guest path logically from rootfs metadata.

This prevents an absolute guest link from accidentally being interpreted as an Android host path during launch checks.

## Bind policy

Structured RuntimeBindSpec values are validated against app-owned host roots.

Current base binds:

- runtime home -> /home/pocket
- runtime tmp -> /tmp

User binds cannot override reserved system paths.

readOnly=true remains fail-closed with READ_ONLY_BIND_UNIMPLEMENTED.

## Environment

Whitelisted baseline plus:

~~~text
PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so
~~~

## PRoot candidate argv

Built as List<String>, never a host shell string.

~~~text
<nativeLibraryDir>/libproot.so
-0
-r <rootfs-data>
-w /home/pocket
-b <host-home>:/home/pocket!
-b <host-tmp>:/tmp!
<guest-entrypoint>
~~~

Even a valid candidate remains blocked by EXECUTOR_NOT_ENABLED / EXECUTOR_NOT_IMPLEMENTED.

## Supervisor

RuntimeProcessSupervisor provides the bounded one-shot host-process primitive:

- one active process;
- explicit environment;
- merged/drained output;
- output cap;
- timeout;
- destroy/force destroy.

It is not wired to PRoot yet.

## Supply chain

PRoot source metadata is pinned but binaries are not bundled.

Android CI rejects unreviewed PRoot artifacts in both app/src and the built APK.

## Next executor gate

Executor enablement requires:

- source audit;
- artifact build/audit;
- licensing package;
- Android link behavior device test;
- PRoot loader/device test;
- process cleanup test.
