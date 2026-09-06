# Linux ARM runtime — Alpha 4 plan

Current state:

- Runtime Manifest: **IMPLEMENTED**
- rootfs integrity staging: **IMPLEMENTED**
- packaged NDK Runtime Host source: **IMPLEMENTED**
- pure manifest/hash logic: **STATICALLY VALIDATED**
- JNI/APK build/load: **NOT CI VALIDATED**
- rootfs extraction: **DESIGN**
- Linux execution: **DESIGN**

## Android W^X constraint

PocketPC targets API 37. Android's target-29+ behavior prevents direct execve of arbitrary executable code from the writable app home directory.

Architecture:

~~~text
APK
└── native Runtime Host / loader code    [executable package path]

app-private data
└── verified rootfs                       [data, writable]
~~~

Do not regress to a design that simply downloads proot or Linux ELF files into filesDir and executes them directly.

## L0 — runtime integrity

Alpha 4 implements most of L0-prep:

- manifest;
- ABI gate;
- declared size;
- SHA-256;
- fail-closed staging;
- inventory/removal.

Still required:

- CI build of NDK library;
- device proof that the native host loads;
- archive format decision;
- extraction that rejects path traversal, unsafe symlinks, device nodes and escaping destinations.

## L1 — execution substrate research

Candidates must be tested, not assumed:

1. APK-packaged native loader/runtime host;
2. system-linker execution approach for rootfs ELFs;
3. proot-based path where the proot/loader executable itself is packaged compliantly;
4. direct JNI/in-process strategies for specific tools where appropriate.

Selection criteria:

- current Android compatibility;
- targetSdk 37 compatibility;
- SELinux/seccomp behavior;
- device/OEM variance;
- stability;
- performance;
- licensing;
- ability to supervise/terminate processes.

## L2 — first Linux proof

Pass only after a physical-device log proves:

1. packaged runtime host loaded;
2. verified aarch64 rootfs prepared;
3. Linux /bin/sh-equivalent launched through the selected substrate;
4. uname/filesystem/basic process commands;
5. create/read a file in runtime home;
6. clean stop and restart;
7. no root;
8. no unrestricted Android storage permission.

Graphical Linux begins only after this.
