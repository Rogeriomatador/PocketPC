# PocketPC runtime and mobile desktop — 12 October 2026

Base: `d3a823c3df7824040ee48c001777b6a736b583dd`, continuing
`improve/alpha22-desktop-continuity` and draft PR #6.

## Resulting behavior

- PRoot receives `LD_LIBRARY_PATH` pointing to the APK's native library directory.
  Its dynamically linked `libtalloc` and `libandroid-shmem` dependencies have no
  RPATH/RUNPATH and need that Android linker search path.
- The Linux bind plan exposes exact SYSTEM-owned `/proc`, `/dev/null`, `/dev/zero`,
  `/dev/random` and `/dev/urandom` pairs. It provides app-private `/dev/shm` and
  copies the active Android network's DNS into an atomic resolver overlay.
  No public DNS server is substituted when the network supplies none.
- User binds cannot replace reserved kernel/home/temp subdirectories. Android UID
  permissions still apply; PRoot is not a VM or a security boundary for Windows code.
- Rootfs probes check usable null/random devices, readable process maps and writable
  shared memory, and report DNS configuration separately from internet connectivity.
- Wine version queries use Box64. Wine probes and app launches share guest binary
  and library search paths for translated child processes.
- Each selected application can save a GDI or DXVK profile and startup arguments.
  Arguments remain positional data through the shell, including quotes, spaces,
  Windows paths and shell-looking operators. Unclosed quotes and oversized input
  are rejected. This is a grouping parser, not a shell or a full Windows command-line parser.
- GDI can be selected for classic applications other than WinRAR. A known Roblox
  Player/Studio executable still requires Direct3D, even if a caller requests GDI.
  Choosing a profile never establishes application compatibility.
- EXE targets are hash-checked and inspected for real PE machine/header/subsystem
  information. x64 GUI/console candidates can proceed through the existing gates;
  x86, Windows ARM64, DLLs, native drivers and malformed headers are rejected with
  distinct reasons. Structural inspection does not establish a trusted publisher
  or guarantee that Wine can load the program.
- Apps start in the imported executable's directory, with the directory passed as
  a positional argument. Import still stages one selected file; portable programs
  requiring adjacent DLLs/data need a future complete-directory import path.
- All guest windows share one viewport transform, preserving aspect ratios,
  relative positions and dialog overlap in portrait/landscape. Per-window stretching
  and independent clamping are removed.
- Frame preview reuses one mutable Bitmap per size. This removes per-frame Bitmap
  allocation; no performance/FPS improvement is claimed without device measurement.
- Accepted keyboard downs are released on focus loss, hidden/removed windows and
  component disposal. A basic touch keyboard sends real down/up sequences and
  Ctrl/Alt/Shift chords to the selected Windows window. Unicode IME composition,
  accented text entry and held game-control gestures are not implemented by this keyboard.
- Probe storage advances to v10 because the Linux launch contract changed. Old
  results do not validate the new contract; files, profiles and Wine prefixes remain
  in their existing stores.
- Five outdated source checks now follow the current download bridge, explicit
  Google Play boundary, file-open coordinator, architecture schema and active v51
  runtime. Historical v50 source checks no longer demand a v50 production workflow.

## Local validation

| Check | Result | Scope |
|---|---|---|
| Kotlin + Java compilation | PASS | Current Android sources |
| JVM tests | PASS: 574 tests, zero failures/errors/skips | Host JVM; includes real host-shell positional/translator fixtures |
| Android lint | PASS, zero errors | Warnings/hints remain; no physical UX claim |
| Native runtime host | PASS | NDK r29, API 26, ARM64 and x86-64, `-Wall -Wextra -Werror` |
| PRoot + loader + talloc + android-shmem | PASS | Pinned ARM64 NDK build; ELF and source-license audits passed |
| PRoot validation bundle | PASS | Candidate staging; production approval unchanged |
| Validation APK | PASS | Both native host ABIs plus the four ARM64 PRoot components and validation manifest |
| Python source/verifier checks | 72 PASS | Two broker tests and the separate native integration runner could not complete because this environment denies AF_UNIX socket creation |
| Android installation/execution | NOT_EXECUTED | No connected physical Android device |
| PRoot guest shell, Box64, Wine, DXVK/Present, audio/network and Roblox | NOT_EXECUTED on Android | Build and host tests do not promote these gates |

The APK uses the development/debug signing identity and `LOCAL_UNPINNED` source
metadata. It is a packaging validation artifact, not a signed public OTA release.
Binary hashes and modified-source hashes are in
[`PC_RUNTIME_ADVANCE_2026-10-12.json`](PC_RUNTIME_ADVANCE_2026-10-12.json).

Reproduction with the pinned toolchain:

```text
python3 scripts/build-proot-ndk.py --ndk <NDK-r29> --work <external-build> --source-cache <external-source-cache> --jobs 2
python3 scripts/audit-proot-source-licenses.py --source-root <external-build>/sources --out <license-audit.json>
python3 scripts/stage-proot-apk-candidate.py --artifact-dir <external-build>/quarantine/arm64-v8a --audit <external-build>/proot-elf-audit.json --out <external-staging>
python3 scripts/make-proot-device-validation-bundle.py --staged-dir <external-staging> --license-audit <license-audit.json> --source-cache <external-source-cache> --out <external-validation-bundle>
gradle --no-daemon -Ppocketpc.prootValidationCandidateDir=<external-validation-bundle> :app:testDebugUnitTest :app:lintDebug :app:assembleValidation
```

## Remaining critical path

1. Execute the packaged PRoot candidate and rootfs probe on an Android device;
   capture the actual linker, device/proc and filesystem results.
2. Validate Box64, Wine Win64/processes, GDI window/pointer/keyboard and prefix
   initialization against the exact installed guest packages.
3. Validate DXVK and continuous presentation with device GPU synchronization,
   then audio, external networking, process lifetime and sustained performance.
4. Attempt individual applications only after their prerequisites pass. Roblox
   Windows remains unvalidated and subject to the publisher's compatibility policy.

The supported architecture is viable for some Windows software, as demonstrated
by Winlator's Wine/Box64 implementation. It cannot guarantee all Windows programs.
Roblox's official requirements list Windows 10/11 64-bit and explicitly exclude Linux.

Primary references reviewed:

- [PRoot: rootfs and explicit kernel binds](https://proot-me.github.io/)
- [Box64 binary and library search paths](https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md)
- [Winlator's implementation and component list](https://github.com/brunodev85/winlator/blob/main/README.md)
- [Android desktop windowing guidance](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-desktop-windowing)
- [Roblox's official operating-system requirements](https://en.help.roblox.com/hc/en-us/articles/203312800-Computer-Hardware-Operating-System-Requirements)
