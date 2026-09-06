# PocketPC architecture — draft 0.10

## Layer 0 — Android host

Lifecycle, SAF, storage, input, thermal/memory APIs, NDK and Vulkan.

## Layer 1 — desktop shell

Files, Terminal, Runtimes, System, Performance.

## Layer 2 — runtime package pipeline

STAGED_VERIFIED -> INSTALLED_DATA.

## Layer 3 — guest filesystem

INSTALLED_DATA -> LINKS_PREPARED.

Includes metadata parsing, guest path resolution, hardlink identity checks and NOFOLLOW cleanup.

## Layer 4 — execution policy foundation

Bind policy, environment, argv planning, launch planner and one-shot process supervisor.

Executor remains disabled.

## Layer 5 — substrate supply chain

Source lock, source audit, quarantine build, ELF/dependency audit, Android packaging blockers and artifact candidate evidence.

## Layer 6 — runtime artifact attestation

Embedded approval manifest, policy digest binding, final artifact-lock requirement and nativeLibraryDir hash verification.

## Layer 7 — device evidence [Alpha 10]

User-triggered app-private probes for:

- relative symlink;
- absolute symlink;
- hardlink;
- NOFOLLOW cleanup;
- external target preservation;
- native host/substrate state.

Results are persisted as JSON plus SHA-256 sidecar.

## Layer 8 — future reviewed PRoot executor

Not implemented/enabled.

## Layer 9 — graphics

Native Vulkan capability probe exists. Controlled renderer remains future work.
