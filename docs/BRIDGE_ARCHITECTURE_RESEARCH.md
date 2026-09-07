# PocketPC Bridge Architecture Research

Status: DESIGN + IMPLEMENTED PROBES. No privileged bridge is claimed as working
until it is physically tested on the target device.

## Goal

Make PocketPC components communicate more like a desktop operating system while
respecting Android security boundaries.

## Layer 0 — normal Android APIs

Already usable without special privilege:

- Binder for local service communication;
- Activity / Service / BroadcastReceiver;
- PackageManager;
- DownloadManager;
- Storage Access Framework;
- DisplayManager;
- MediaCodec / HardwareBuffer;
- VirtualDisplay for PocketPC-owned content;
- Wi-Fi Direct / Wi-Fi Aware when advertised.

This remains the default/fallback layer.

## Layer 1 — PocketPC Core Bus

Future internal architecture:

- one PocketPC core service owns desktop/system state;
- UI apps become clients;
- StateFlow can remain for same-process state;
- Binder is preferred if services are split into multiple processes;
- AIDL should only be introduced when cross-process/cross-app IPC actually requires it.

Android AIDL documentation explicitly recommends ordinary Binder instead of AIDL
when concurrent cross-app IPC is not required.

Reference:
https://developer.android.com/develop/background-work/services/aidl

## Layer 2 — Termux bridge

Termux exposes a supported RUN_COMMAND intent for third-party apps.

Requirements:

1. Termux installed.
2. PocketPC requests com.termux.permission.RUN_COMMAND.
3. User grants that permission.
4. Termux has allow-external-apps=true in ~/.termux/termux.properties.
5. Every command remains explicit/user-controlled.

This can provide a Linux-like user shell without pretending PocketPC itself has
Android shell/root privileges.

Current PocketPC implementation:

- package visibility for Termux;
- RUN_COMMAND permission declaration;
- Research Lab detects installation and permission state;
- no command execution has been implemented yet.

Reference:
https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent

## Layer 3 — Shizuku bridge

Shizuku can expose Android system APIs using adb-shell identity. On Android 11+
the Shizuku service can be started using Wireless Debugging without a PC after
the user completes pairing/start steps.

Potential PocketPC uses, only after explicit opt-in and capability checks:

- richer package management;
- selected shell-level diagnostics;
- selected system operations unavailable to normal apps;
- experimental desktop/window controls where adb-shell actually has permission.

Important:

- Shizuku is not root in normal Wireless Debugging mode;
- adb-shell permissions vary by Android/OEM;
- PocketPC must probe each operation instead of assuming access;
- no Shizuku API dependency is included yet.

Current PocketPC implementation only detects whether a Shizuku manager package is
visible. It does not request Shizuku permission or execute privileged calls.

Reference:
https://github.com/RikkaApps/Shizuku-API

## Layer 4 — remote PocketPC display

The existing Research Lab already probes:

- private VirtualDisplay;
- HardwareBuffer with VIDEO_ENCODE usage;
- hardware Surface video encoders;
- AV1 / HEVC / AVC preference;
- Wi-Fi Direct;
- Wi-Fi Aware.

If the physical device confirms these pieces together, the next prototype can be:

PocketPC render surface
→ GPU/Surface
→ hardware MediaCodec encoder
→ local Wi-Fi transport
→ receiver on another phone/tablet/PC/browser-compatible client

The phone can then become a touchpad/keyboard/control surface.

This is particularly relevant to the current POCO X7, whose official Xiaomi
documentation says wired USB-C video output is not supported.

A successful capability probe is only foundation evidence, not proof that the
complete remote-desktop pipeline works.

## VirtualDeviceManager / Computer Control

Android exposes VirtualDeviceManager APIs, but newer Computer Control APIs are
restricted to approved agent applications/roles and policy allowlists. PocketPC
must not treat service presence as permission to automate arbitrary apps.

Research Lab therefore records separately:

- service available;
- CREATE_VIRTUAL_DEVICE permission state;
- ACCESS_COMPUTER_CONTROL permission state.

Reference:
https://developer.android.com/reference/android/companion/virtual/VirtualDeviceManager

## Accessibility is not a generic game-input backend

AccessibilityService can dispatch gestures when explicitly enabled and configured,
but Android documents accessibility services as facilities to assist users with
disabilities. PocketPC will not silently repurpose Accessibility as a generic
cross-app game injection layer.

Reference:
https://developer.android.com/reference/android/accessibilityservice/AccessibilityService

## AppSearch

Jetpack AppSearch is a promising basis for a unified Start/Search experience:

- installed apps;
- PocketDrive files;
- settings;
- recent documents;
- game compatibility profiles;
- commands.

It offers local full-text indexing and low-I/O search.

This is DESIGN until a dependency/schema/indexer is implemented and tested.

Reference:
https://developer.android.com/develop/ui/views/search/appsearch

## On-device APK workflow

PocketPC already has:

- REQUEST_INSTALL_PACKAGES;
- unknown-source permission routing;
- APK package/version/signature/hash checks for self-update;
- APK opening from PocketDrive.

The repository also includes:

scripts/termux-install-published.sh

It downloads only a published stable feed APK, validates metadata and SHA-256,
then asks Android/Termux to open the package installer.

If updates/stable.json has published=false, the helper refuses to invent a build.

## Evidence rules

- Installed package detected != bridge works.
- Permission granted != requested operation works.
- VirtualDisplay created != remote desktop works.
- Encoder discovered != sustained low-latency stream works.
- Shizuku installed != shell operation allowed.
- Termux installed != RUN_COMMAND configured.
- NOT_EXECUTED never means PASS.
