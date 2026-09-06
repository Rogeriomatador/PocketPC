# Linux ARM runtime — design gate

Status: **DESIGN**. PocketPC does not yet ship a Linux rootfs.

## Goal

Run an ARM64 Linux userspace under the normal Android application security boundary, supervised by PocketPC, before attempting x86/x64 Windows compatibility.

## Required components

- RuntimeManifest: version, architecture, source, SHA-256, license metadata.
- RootfsManager: download/import, verify, extract, update and remove.
- ProcessSupervisor: start/stop, stdout/stderr, timeout, crash cleanup.
- EnvironmentBridge: HOME, TMP, locale and controlled user-selected storage.
- TerminalBridge: initial pipe-based proof; PTY only after the simple path is validated.
- RuntimeLogs: exact command/runtime version and exit state.

## Open implementation decision

The exact no-root userspace mechanism is intentionally not declared implemented yet. Candidates must be evaluated for Android restrictions, syscall/filesystem behavior, maintenance, performance and licensing.

## Linux gate L0

Before graphical Linux:

1. validated ARM64 rootfs manifest;
2. integrity verification;
3. start shell;
4. uname and basic filesystem commands;
5. create/read file inside runtime home;
6. stop/cleanup reliably;
7. no unrestricted storage permission;
8. repeatable result on at least one physical device.

Only after L0 should package management and graphics be added.
