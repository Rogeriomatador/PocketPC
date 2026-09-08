# PocketPC Update Architecture

## Goal

After one signed bootstrap installation, PocketPC should be able to discover future
versions, download them without PC/ADB commands, verify them and request an in-place
self-update using Android's package installer.

The intended steady-state path is:

```text
main
  -> signed release build
  -> immutable HTTPS APK
  -> stable.json
  -> WorkManager check on phone
  -> DownloadManager
  -> SHA-256 / package / version / source revision / signing checks
  -> PackageInstaller session
  -> automatic install when Android permits
     OR one Android confirmation when the platform requires it
```

## Android boundary

PocketPC is not a privileged/system package manager.

Alpha 21 uses `PackageInstaller.SessionParams` and, on Android versions that expose
it, requests `USER_ACTION_NOT_REQUIRED`. It also declares
`UPDATE_PACKAGES_WITHOUT_USER_ACTION`.

This is **best-effort automatic installation**, not a promise that every Android OEM
will install silently. Android can still return `STATUS_PENDING_USER_ACTION`; when
that happens PocketPC follows the system confirmation flow.

Alpha 21 also creates a high-priority update notification when user action is required.
On Android 13+ the Update Center exposes the `POST_NOTIFICATIONS` permission explicitly.
The receiver still attempts the immediate confirmation flow when allowed, while the
notification provides a fallback for OEM/background-activity restrictions.

PocketPC never bypasses Android package-signing or installation security.

## Alpha 21 implementation

Implemented in source:

1. stable feed at `updates/stable.json`;
2. Este PC > Atualizações;
3. launch-time update check;
4. periodic WorkManager check every six hours;
5. automatic download on unmetered network when enabled;
6. DownloadManager transport;
7. HTTPS-only published APK URL;
8. SHA-256 verification;
9. package-name verification;
10. versionCode freshness verification;
11. embedded Git source-revision verification;
12. installed/candidate signing-certificate compatibility verification;
13. unknown-source authorization flow;
14. automatic verified-install preference;
15. PackageInstaller session staging;
16. duplicate-install-attempt protection;
17. result receiver for success/failure/pending user action;
18. fallback to Android confirmation when required.

The current Alpha 21 stable feed remains `published=false`. Therefore no remote APK is
currently advertised to installed devices.

## Signing and release publication

Android only permits an in-place update when the candidate APK has a compatible signing
identity.

PocketPC now pins the public SHA-256 certificate fingerprint observed in the physically
validated Alpha 20 evidence bundle at:

`updates/bootstrap-signer.json`

The release workflow extracts the signer certificate from the candidate APK and runs
`scripts/verify-bootstrap-signer.py`. Publication fails if the candidate signer does not
match the physically observed bootstrap identity. This metadata is public certificate
information only; no private signing key is committed.

Until an explicit signing-lineage migration is designed and validated, the publisher is
therefore constrained to the Alpha 20 bootstrap signer.

PocketPC now contains a fail-closed GitHub Actions publisher:

`.github/workflows/publish-update.yml`

It is designed to:

- refuse publication when signing secrets are absent;
- materialize a keystore only inside the CI runner;
- run PocketPC source policies;
- build/test/lint a release APK;
- embed the exact Git source revision;
- verify the signed APK with `apksigner`;
- create an immutable GitHub Release asset;
- generate `stable.json` from the real APK SHA-256;
- validate the feed before publishing it.

Expected protected secrets:

- `POCKETPC_SIGNING_KEYSTORE_BASE64`
- `POCKETPC_SIGNING_STORE_PASSWORD`
- `POCKETPC_SIGNING_KEY_ALIAS`
- `POCKETPC_SIGNING_KEY_PASSWORD`

No private signing key is committed to the repository.

Until a compatible long-lived signing identity is configured:

- updater source: IMPLEMENTED;
- WorkManager scheduling: IMPLEMENTED;
- PackageInstaller staging: IMPLEMENTED;
- signed remote release publication: BLOCKED;
- Alpha 21 software test: NOT_EXECUTED;
- Alpha 21 physical auto-update: NOT_EXECUTED.

## Current Alpha 20 installation and signing migration

The physically validated Alpha 20 APK was installed before the long-lived release
publisher existed.

A future release APK can update Alpha 20 in-place only if its signing identity is
compatible with the currently installed APK.

If a new long-lived release key is different, Android will not accept it as an in-place
update without an approved signing-key migration. PocketPC must not work around this by
weakening signature checks.

This means one bootstrap/signing transition may still be required before the desired
steady state of “ChatGPT/repository changes -> phone updates itself” can be reached.

## One-time Alpha 20 -> Alpha 21 bootstrap

The Alpha 20 APK that is physically installed on the POCO does not contain the Alpha
21 updater code. Therefore one transition is unavoidable before the steady-state
self-update path exists.

The repository now contains:

`scripts/bootstrap-update-signing-windows.ps1`

It is fail-closed:

1. reads the candidate Windows keystore;
2. exports only its public certificate;
3. computes the certificate SHA-256;
4. compares it with the signer observed in the physical Alpha 20 evidence bundle;
5. refuses to send any GitHub Secret when the signer differs;
6. when it matches, stores the signing material only in protected GitHub Actions
   Secrets;
7. optionally triggers `publish-update.yml`.

The intended one-time bootstrap command is:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\bootstrap-update-signing-windows.ps1 `
  -PublishNow
```

This command requires GitHub CLI to be authenticated once. The private keystore is not
committed to the repository.

After a compatible signed Alpha 21 release is published, Alpha 20 still needs to receive
that bootstrap APK once. This can be done by downloading/tapping the signed APK on the
phone or by the existing validated PC install flow.

After Alpha 21 is installed, the normal intended path becomes:

```text
source changes
  -> signed published APK
  -> stable.json
  -> PocketPC checks feed
  -> automatic download when policy allows
  -> verification
  -> PackageInstaller
  -> silent completion when Android permits
     or Android confirmation when required
```

No ADB or PowerShell command is intended for routine future updates.

## Local publisher fallback

GitHub Actions is the preferred steady-state publisher, but PocketPC also includes a
Windows fallback:

`scripts/publish-update-local-windows.ps1`

This path reuses the pinned PocketPC toolchain and is fail-closed. It:

1. requires a clean repository and fast-forward pull;
2. validates the local keystore against the physically pinned Alpha 20 signer;
3. runs the strict local debug/policy build unless explicitly skipped;
4. builds/tests/lints a signed release APK;
5. verifies the release signing certificate against the bootstrap pin;
6. creates an immutable GitHub Release;
7. generates `updates/stable.json` from the real APK SHA-256 and source revision;
8. reruns the update-feed policy;
9. commits and pushes only the published feed.

This fallback exists so a GitHub runner outage does not permanently block binary
publication. It still requires the Windows PC to be online, authenticated with GitHub
CLI and holding the compatible signing keystore.

It is not the routine phone update path. Once a release/feed exists, the phone updater
continues to use the same in-app verification/install flow.

## Data preservation

For normal in-place updates with the same compatible signing identity:

- app-private `C:` state remains;
- external `P:` PocketDrive remains;
- browser downloads already imported to `P:Downloads` remain.

If a signing transition ever requires uninstall/reinstall, app-private `C:` state may
be lost. `P:` is intentionally separate from the APK lifecycle and is therefore the
preferred home for user data.

## Evidence rule

A source implementation of the updater is not proof that:

- a signed release exists;
- the stable feed is active;
- the phone downloaded it;
- PackageInstaller accepted it;
- HyperOS completed it without user action.

Those states require real release/build/device evidence.
