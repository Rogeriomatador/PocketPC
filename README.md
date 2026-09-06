# PocketPC — 0.1.0-alpha6

PocketPC is an experimental Android desktop/runtime project aimed at turning a phone into a desktop workstation while preserving honest evidence labels.

## Evidence ladder

- **DESIGN**
- **IMPLEMENTED**
- **STATICALLY VALIDATED**
- **CI VALIDATED**
- **DEVICE TESTED**
- **BENCHMARKED**

A higher label is never inferred from a lower one.

## Alpha 6 — IMPLEMENTED source

Alpha 6 includes all Alpha 5 runtime-package and safe-rootfs work, plus:

- structured host/guest bind model;
- host-path allowlist validation;
- reserved guest mount protection;
- normalized guest path validation;
- minimal Linux environment whitelist;
- PRoot argv planner using List<String> instead of shell-string concatenation;
- app-private runtime home and tmp bind planning;
- one-shot process supervisor with timeout and bounded logs;
- process environment clearing before explicit variables;
- output drain designed to avoid pipe deadlock;
- execution remains explicitly disabled by EXECUTOR_NOT_ENABLED;
- unit-test sources for bind policy, environment, argv planning and supervisor behavior.

## Runtime pipeline

~~~text
manifest/archive
   ↓
STAGED_VERIFIED
   ↓
safe TAR/TAR.GZ extraction
   ↓
INSTALLED_DATA
   ↓
bind/env/argv planning
   ↓
[EXECUTOR_NOT_ENABLED]
~~~

Linux is still **not claimed executable**.

## Static validation

The pure Kotlin schema-v2 + safe TAR smoke passed locally:

- valid schema v2 accepted;
- valid TAR extracted;
- output file content matched;
- ../ traversal archive rejected;
- no escaped file was created.

That does not validate Android, Gradle, JNI, PRoot or a device.

## PRoot research baseline

Current research baseline remains the Termux-maintained PRoot package 5.1.107.89, GPL-2.0, with libandroid-shmem and libtalloc dependencies.

No PRoot binary is bundled. CI intentionally rejects unreviewed PRoot-named libraries.

## Graphics

Native Vulkan capability enumeration exists. No renderer/vGPU performance claim exists yet.

## Version

- app versionCode: 6
- app versionName: 0.1.0-alpha6
- compile/target SDK: 37
- min SDK: 26
- NDK: 29.0.14206865
- CMake: 3.22.1

## CI status

Still **not CI validated**. GitHub-hosted runs have been failing before the first workflow step with no job steps/logs. That remains Issue #3 and is not evidence of source compilation failure.

## Next gate

1. CI runner allocation.
2. Android build/lint/tests.
3. device-test native host and Vulkan probe.
4. device-test schema-v2 rootfs install.
5. complete PRoot provenance/license/build audit.
6. implement guest link semantics.
7. enable a tightly gated non-interactive PRoot smoke command.
8. PTY and interactive shell only after the one-shot path is proven.
