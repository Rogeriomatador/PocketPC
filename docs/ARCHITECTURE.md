# PocketPC architecture — draft 0.9

## Layer 0 — Android host

Lifecycle, SAF, storage, input, thermal/memory APIs, NDK and Vulkan.

## Layer 1 — desktop shell

Files, Terminal, Runtimes, System, Performance.

## Layer 2 — runtime package pipeline

STAGED_VERIFIED -> INSTALLED_DATA.

## Layer 3 — guest filesystem

INSTALLED_DATA -> LINKS_PREPARED.

Includes metadata parser, guest link resolver, hardlink verification and NOFOLLOW cleanup.

## Layer 4 — execution policy

Bind policy, environment, PRoot argv planner, process supervisor and launch planner.

Executor remains disabled.

## Layer 5 — substrate supply chain

- source lock;
- source archive auditor;
- ELF artifact contract;
- quarantine build;
- ELF/DT_NEEDED/SONAME auditor;
- Android packaging blocker detection;
- candidate evidence.

## Layer 6 — runtime artifact attestation [Alpha 9]

- embedded approval manifest;
- policy asset digest binding;
- final artifact-lock requirement;
- nativeLibraryDir byte/hash verification;
- extra sensitive artifact rejection.

prootReady is derived from attestation, not file names.

## Layer 7 — future reviewed PRoot executor

Not implemented/enabled.

## Layer 8 — graphics

Native Vulkan capability probe exists. Controlled renderer remains future work.
