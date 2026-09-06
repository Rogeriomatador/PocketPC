# Execution model — Alpha 6

Status: **IMPLEMENTED foundation / execution disabled**

## Goal

Prepare the Linux execution path so no runtime command is assembled through a shell string and no host path can be mounted outside an explicit allowlist.

## Bind model

PocketPC represents mounts as structured RuntimeBindSpec values:

- hostPath
- guestPath
- readOnly
- purpose
- authority

Validation rules include:

- host canonical path must remain under an allowed app-owned root;
- guest path must be absolute and normalized;
- dot-dot traversal is rejected;
- duplicate guest mount points are rejected;
- PRoot syntax characters such as colon/exclamation are rejected in guest paths;
- user binds cannot override reserved locations such as /proc, /sys, /dev, /tmp or /home/pocket.

Alpha 6 creates only two system-owned base binds:

- app-private runtime home -> /home/pocket
- app cache runtime tmp -> /tmp

User SAF storage is **not** directly bind-mounted yet.

## Environment

RuntimeEnvironment builds a minimal whitelist:

- HOME
- USER
- LOGNAME
- SHELL
- PATH
- TMPDIR
- LANG
- LC_ALL
- TERM

The process environment is cleared before the explicit map is applied.

## PRoot argv planning

ProotInvocationPlanner builds a List<String>, not a shell command string.

Candidate shape:

~~~text
/native/libproot.so
-0
-r <rootfs-data>
-w /home/pocket
-b <host-home>:/home/pocket!
-b <host-tmp>:/tmp!
/bin/sh
~~~

The exclamation suffix requests non-dereferencing of the guest bind location in PRoot syntax.

Even when a syntactically valid argv can be produced, Alpha 6 appends EXECUTOR_NOT_ENABLED and reports ready=false.

## Process supervisor

RuntimeProcessSupervisor provides bounded one-shot process execution:

- one active supervised process;
- explicit argv;
- cleared/whitelisted environment;
- merged stdout/stderr;
- continuous drain to avoid pipe deadlock;
- output retention cap;
- timeout;
- graceful destroy followed by destroyForcibly fallback.

This is infrastructure for the first non-interactive Linux smoke command. It is **not a PTY** and is not yet wired to PRoot.

## Remaining execution blockers

- reviewed PRoot binaries not bundled;
- guest link semantics not implemented;
- executor intentionally disabled;
- no physical-device proof;
- no PTY;
- no process-tree kill validation on Android.
