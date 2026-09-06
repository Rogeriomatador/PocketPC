# PocketPC architecture — draft 0.7

## Layer 0 — Android host

Lifecycle, SAF, storage, input, thermal/memory APIs, NDK and Vulkan.

## Layer 1 — desktop shell

Files, Terminal, Runtimes, System, Performance.

## Layer 2 — runtime package pipeline

~~~text
manifest/archive
   ↓
STAGED_VERIFIED
   ↓
safe extraction
   ↓
INSTALLED_DATA
~~~

## Layer 3 — guest filesystem [Alpha 7]

- RootfsMetadata
- GuestPath
- RootfsGuestResolver
- RootfsLinkManager
- SafeTreeOps

State transition:

~~~text
INSTALLED_DATA
   ↓
LINKS_PREPARED
~~~

Extraction and link materialization are intentionally separate transactions.

## Layer 4 — execution policy foundation

- RuntimeBindPolicy
- RuntimeEnvironment
- ProotInvocationPlanner
- RuntimeProcessSupervisor
- RuntimeLaunchPlanner

Executor remains disabled.

## Layer 5 — execution substrate

DESIGN / not bundled:

- libproot.so alias
- libproot_loader.so alias
- libandroid-shmem.so
- libtalloc.so

## Layer 6 — Linux runtime services

Future:

- reviewed PRoot executor;
- proc/dev/sys policy;
- PTY/signals;
- process tree lifecycle;
- package/bootstrap;
- user-storage bridge.

## Layer 7 — graphics

Native Vulkan capability probe exists. Controlled renderer remains next after the runtime execution gates.
