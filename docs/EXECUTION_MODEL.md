# Execution model — Alpha 6

Status: **IMPLEMENTED foundation / execution disabled**

## Bind model

PocketPC represents mounts as structured RuntimeBindSpec values:

- hostPath
- guestPath
- readOnly
- purpose
- authority

Validation includes:

- host canonical path under an allowed app-owned root;
- normalized absolute guest path;
- dot-dot rejection;
- duplicate guest mount rejection;
- reserved guest paths;
- PRoot syntax character rejection.

Base system binds:

- app-private runtime home -> /home/pocket
- app cache runtime tmp -> /tmp

User SAF storage is not directly bind-mounted yet.

### Read-only behavior

Alpha 6 does not claim a proven read-only PRoot bind mode. If readOnly=true is requested, ProotInvocationPlanner adds READ_ONLY_BIND_UNIMPLEMENTED and does not emit a candidate argv.

## Environment

RuntimeEnvironment creates the guest baseline:

- HOME
- USER
- LOGNAME
- SHELL
- PATH
- TMPDIR
- LANG
- LC_ALL
- TERM

For the proposed PRoot substrate it additionally pins:

- PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so

The loader alias is a PocketPC packaging adaptation of upstream PRoot's unbundled ARM64 loader.

## PRoot argv planning

ProotInvocationPlanner builds a List<String>, not a shell string.

Candidate shape:

~~~text
<nativeLibraryDir>/libproot.so
-0
-r <rootfs-data>
-w /home/pocket
-b <host-home>:/home/pocket!
-b <host-tmp>:/tmp!
/bin/sh
~~~

The exclamation suffix is PRoot's no-dereference form for the guest bind destination. It is not being treated as a read-only flag.

Even a valid candidate plan receives EXECUTOR_NOT_ENABLED and reports ready=false.

## Process supervisor

RuntimeProcessSupervisor provides bounded one-shot process execution:

- one active process;
- explicit argv;
- cleared then explicitly populated environment;
- merged stdout/stderr;
- continuous pipe drain;
- retained-output cap;
- timeout;
- destroy/force-destroy fallback.

It is not yet wired to PRoot and is not a PTY.

## Supply-chain boundary

third_party/proot/LOCK.json pins source metadata. scripts/audit-proot-sources.py independently downloads and hashes those sources when network access exists.

No PRoot binary is bundled yet.

## Remaining blockers

- source archive independent audit not yet run in CI;
- PRoot build/artifact audit incomplete;
- guest links are metadata only;
- executor intentionally disabled;
- no physical-device proof;
- no PTY;
- process-tree kill semantics not device validated.
