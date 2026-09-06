# PocketPC architecture — draft 0.11

## Layer 0 — Android host

Lifecycle, SAF, storage, input, package/signing identity, NDK and Vulkan.

## Layer 1 — desktop shell

Files, Terminal, Runtimes, System, Performance.

## Layer 2 — runtime package pipeline

STAGED_VERIFIED -> INSTALLED_DATA.

## Layer 3 — guest filesystem

INSTALLED_DATA -> LINKS_PREPARED with guest link semantics and NOFOLLOW cleanup.

## Layer 4 — execution policy foundation

Bind/environment policy, argv planning and bounded process supervision.

Executor remains disabled.

## Layer 5 — substrate supply chain

Source locks, archive audit, artifact quarantine, ELF/dependency audit and Android packaging gates.

## Layer 6 — runtime artifact attestation

Approval manifest, policy digest binding and nativeLibraryDir artifact hashes.

## Layer 7 — device evidence

Physical filesystem/self-test harness.

## Layer 8 — evidence identity and export [Alpha 11]

- BuildConfig source revision;
- installed APK version;
- signing-certificate hashes;
- installer identity;
- evidence bundle;
- per-entry hashes;
- host verifier;
- SAF export.

## Layer 9 — future PRoot executor

Not implemented/enabled.

## Layer 10 — graphics

Native Vulkan capability enumeration exists. Controlled renderer remains future work.
