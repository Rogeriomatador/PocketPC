# PocketPC — 0.1.0-alpha10

PocketPC is an experimental Android desktop/runtime project aimed at turning a phone into a desktop workstation while preserving strict evidence labels.

## Alpha 10 — Device Evidence Harness

Alpha 10 keeps the Alpha 9 runtime artifact attestation gate and adds a non-destructive physical-device self-test harness.

The System panel can now run a test that uses only app-private temporary directories and does not execute PRoot, require root, or touch shared storage.

The self-test checks:

- relative symlink creation/readback;
- absolute symlink creation/readback;
- hardlink creation and inode identity;
- NOFOLLOW root-tree cleanup;
- preservation of an external symlink target.

It also records:

- device manufacturer/model/API/ABIs;
- PocketPC native host state;
- nativeLibraryDir;
- substrate approval/attestation state;
- substrate component presence/readability/executable bits;
- approval errors;
- Vulkan probe text.

## Evidence output

The app writes:

~~~text
<noBackupFilesDir>/device-evidence/
  device-evidence-latest.json
  device-evidence-latest.sha256
~~~

The JSON is promoted transactionally and a SHA-256 sidecar is written afterward.

This does not automatically upgrade any project evidence label. The file must come from an exact APK/commit and be reviewed.

## Runtime status

The embedded PRoot approval remains:

~~~text
approved=false
status=NOT_APPROVED
~~~

Therefore prootReady remains false and the Linux executor remains disabled.

## Version

- versionCode: 10
- versionName: 0.1.0-alpha10
- compile/target SDK: 37
- min SDK: 26

## Current CI limitation

GitHub-hosted workflows continue to fail before step 1 with no steps/logs. This remains infrastructure-only evidence.

## Next gates

1. get an APK build from a functioning runner/environment;
2. run Device Evidence Harness on physical ARM64 Android;
3. retain JSON + SHA-256 for exact APK;
4. run source/quarantine audits;
5. resolve Android packaging blockers;
6. create final artifact lock only after real evidence;
7. keep Linux executor disabled until attestation succeeds.
