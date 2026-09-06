# PocketPC architecture — draft 0.6

## Android host

Lifecycle, input, SAF, storage, thermal/memory APIs, NDK and Vulkan.

## Desktop shell

Files, Terminal, Runtimes, System, Performance.

## Runtime package pipeline

manifest -> STAGED_VERIFIED -> safe extraction -> INSTALLED_DATA

## Execution policy layer [IMPLEMENTED foundation]

- RuntimeBindPolicy
- RuntimeBindPlanner
- RuntimeEnvironment
- ProotInvocationPlanner
- RuntimeProcessSupervisor

The layer can validate and construct a candidate execution request, but EXECUTOR_NOT_ENABLED keeps it non-runnable.

## Execution substrate [DESIGN]

Future reviewed PRoot components in nativeLibraryDir.

## Linux services [DESIGN]

- link semantics
- process-tree behavior
- PTY
- signals
- package/bootstrap
- user-storage bridge

## Graphics [G0 source implemented]

Native Vulkan capability probe exists. Controlled renderer is next.

## Performance [PARTIAL]

Advisory governor only until PocketPC owns a measured runtime/renderer workload.
