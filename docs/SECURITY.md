# Security model — Alpha 5

## Current boundary

- ordinary Android app UID;
- no root;
- no MANAGE_EXTERNAL_STORAGE;
- user-selected SAF inputs;
- archive SHA-256 before staging;
- schema/path validation;
- rootfs extraction only from schema-v2 tar/tar.gz;
- fail-closed TAR parser;
- no Android symlink/hardlink materialization;
- transactional staging/install directories;
- free-space budgets and archive/extraction limits;
- no Linux execution claim.

## TAR policy

Rejected:

- absolute archive paths;
- dot-dot traversal;
- backslash path ambiguity;
- duplicate paths;
- entries below a previously recorded link;
- link-after-descendant ambiguity;
- corrupt header checksum;
- device nodes;
- FIFOs;
- unsupported special entry types;
- byte/entry/header/path limits exceeded.

PAX and GNU long-name metadata are parsed with bounded size.

## Link policy

Guest symlink and hardlink information is stored in metadata only.

This prevents extraction writes and recursive cleanup from accidentally following guest links outside the intended data root.

## Third-party substrate

PRoot is not bundled. CI rejects unreviewed PRoot-named libraries until provenance and GPL/dependency obligations are documented.

## Future requirements

- no-shell-string construction of PRoot arguments;
- explicit bind-mount allowlist;
- controlled environment variables;
- process-group termination;
- log/output caps;
- no silent permission expansion;
- device/OEM variance testing;
- runtime package signatures or stronger trust model before automatic downloads.
