# Security model

## Alpha 4 boundary

- normal Android app UID;
- no root;
- no MANAGE_EXTERNAL_STORAGE;
- user-selected SAF roots;
- local Android shell inherits PocketPC app permissions;
- runtime manifest limited to 128 KiB;
- runtime ID/version validated before becoming filesystem path components;
- Linux entrypoint rejects .. segments;
- rootfs declared size enforced while streaming;
- SHA-256 required before staging promotion;
- failed staging is deleted;
- rootfs remains data and is not directly executed.

## Android code execution

PocketPC targets API 37. Do not execute newly downloaded app-home files directly. Native runtime/loader code must be shipped through an Android-compliant executable/package path.

## Before rootfs extraction

Extraction is blocked by design until policy handles:

- ../ and absolute paths;
- symlinks/hardlinks escaping destination;
- device nodes/FIFOs;
- ownership/mode normalization;
- archive bombs and declared/extracted size caps;
- partial extraction cleanup.

## Future downloaded/imported components

- cryptographic hash required;
- source/version/license recorded;
- architecture validated;
- fail closed;
- deterministic process cleanup;
- no silent permission expansion.

Third-party redistribution requires a documented license audit before bundling.
