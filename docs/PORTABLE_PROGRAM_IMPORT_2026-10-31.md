# Program directory import — 31 October 2026

Base: `8586ae397248d479f1ca4e5d6d3781db168f7aaf` on
`improve/alpha22-desktop-continuity`, continuing draft PR #6.

## Problem and resulting behavior

Selecting `Program/bin/app.exe` previously copied only the executable and renamed
it with a hash prefix. Adjacent DLLs, configuration and `../data` were absent.
The runtime can now associate the selected EXE/MSI with a program folder picked
through Android's document picker. Preparation copies that complete folder into
one private directory, preserving executable names, subdirectories and assets.
The launch planner still starts in the executable's own directory and passes
paths/arguments as positional shell data.

In Runtime, use **Selecionar pasta do programa**, then **Salvar perfil** to retain
the association alongside graphics and arguments. **Usar somente este arquivo**
restores single-file behavior; save the profile to retain that choice. Folder
selection persists read permission and does not write to the source folder.

The selected executable must be an Android DocumentsProvider document within the
chosen tree, from the same authority. Membership is established by enumerating
document identities, never by finding a matching basename. Internal non-document
URIs cannot be associated with an external tree: open the EXE from the Explorer
using an Android-selected source folder instead. Revoked permission fails the
operation and requires reselecting the folder.

## Copy and lifecycle contract

- Private staging is published only after the complete walk and selected-file
  checks pass. Failures/cancellation discard staging and preserve earlier copies.
- Limits are 16 GiB of actual bytes, 20,000 entries including directories, and 32
  nested directory levels. Declared sizes are checked when supplied; unknown-size
  files remain bounded by actual bytes read.
- Reject traversal, path separators, control characters, Windows-invalid names,
  reserved DOS device basenames, trailing spaces/dots, case-folded sibling
  collisions, repeated document IDs and provider cycles. Paths are not silently
  sanitized because renaming DLLs/assets can break the application.
- The EXE/MSI's copied length and SHA-256 feed the existing launch integrity
  checks. Sidecars are copied by value; this is not a signed application package
  or a proof of publisher trust. Same-size changes in a live source folder are
  not detected as a coherent snapshot; finish downloads and source writes first.
- If planning fails after import, its new program directory is discarded. Explicit
  dismissal/cancellation of the launch confirmation schedules the same cleanup.
  It does not delete source files or earlier imported programs.
- Once execution is confirmed, the copy is retained for Wine and child processes.
  Each subsequent preparation creates a fresh snapshot. This is not yet an
  installed-program library, a global storage quota or persistent application-state
  migration. Changes inside an earlier copy are not merged into the next one or
  written back to the source folder. Large imports are not resumable.

## Validation

The 15 new `PcProgramDirectoryImporterTest` tests passed against real host files,
using a provider abstraction for opaque document IDs and simulated read failures.
They cover nested DLL/data paths, empty files/directories, missing/renamed targets,
unsafe names, case collisions, cycles, byte/entry/depth limits, changed sizes,
failed reads, rollback preserving a prior import, cancellation, enumeration errors,
invalid roots and independent successful snapshots.

The Kotlin source regression, display protocol lock and Vulkan Compose refresh
Python checks passed. These source checks and host tests do not establish Android
document-picker or Wine execution evidence.

The final combined Android Gradle run passed: **602 JVM tests**, zero failures,
errors or skips; Kotlin/Java compilation; `assembleDebug`; and `lintDebug` with
**zero errors and 134 warnings**. Native rebuilding was disabled. The development
APK has `LOCAL_UNPINNED` source metadata and is not a signed Home Test OTA release.

Reproduce Android validation with the pinned toolchain:

```sh
gradle --no-daemon -Ppocketpc.skipNativeBuild=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/test-kotlin-source-regressions.py
python3 scripts/test-display-bridge-protocol-lock.py
python3 scripts/test-runtime-vulkan-compose-refresh-policy.py
```

## Device gate remains unchanged

Android SAF traversal/permission restoration, UI interactions, directory import
and Wine loading of an EXE with adjacent DLLs/data are **NOT_EXECUTED physically**.
The next execution gate remains packaged PRoot/rootfs on Android, then
Box64/Wine/window/input and graphics/presentation. No readiness evidence is
promoted by importing files. Wine v51 remains stable; v52 remains experimental.
Roblox Windows remains unvalidated. The PR stays draft, without merge or OTA.
