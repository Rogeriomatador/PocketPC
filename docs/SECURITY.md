# Security model

## Current Alpha 3 boundary

- normal Android app UID;
- no root request;
- no MANAGE_EXTERNAL_STORAGE;
- user-selected SAF roots;
- local shell inherits PocketPC app permissions;
- file opening is delegated through Android intents.

The terminal is powerful within the app UID. It can execute commands available to that UID, so future user-facing builds should make that boundary explicit.

## Future runtime requirements

- verify downloaded/imported runtime artifacts by cryptographic hash;
- record source/version/license;
- reject architecture/version mismatches;
- keep runtime files under app-controlled storage unless the user explicitly exposes a SAF tree;
- fail closed on validation errors;
- prevent silent permission expansion;
- provide deterministic cleanup of supervised processes.

## Third-party components

Before redistribution of Linux rootfs, Box64, Wine, DXVK, VKD3D, Mesa or other components, document upstream license, source/version, modification obligations and redistribution terms.
