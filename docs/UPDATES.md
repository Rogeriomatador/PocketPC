# PocketPC Update Architecture

## Goal

After one bootstrap installation, PocketPC should be able to discover future versions,
download them without PC/ADB commands, verify them and hand the verified APK to Android
for an in-place update.

## Standard Android boundary

PocketPC is not a privileged/system installer. On a normal sideloaded phone it cannot
silently replace its own APK. The app can automate discovery, download and verification,
but Android controls unknown-source authorization and final package-install consent.

## Alpha 21 bootstrap

Alpha 20 is the current physically verified installed baseline.

Alpha 21 adds:

1. startup update-feed check;
2. Este PC > Atualizações;
3. stable JSON feed at `updates/stable.json`;
4. background APK download through DownloadManager;
5. SHA-256 verification;
6. package-name/versionCode verification;
7. signing-certificate compatibility verification;
8. Android package-installer handoff.

The Alpha 21 feed remains `published=false` until a signed artifact exists.

## Signing

Android only permits an in-place package update when the new APK is signed by a
compatible signing identity.

Do not commit a keystore/private key to this repository.

For a fully automatic source -> build -> release -> phone path, a stable signing key
must be stored in a secure release system (for example a protected CI secret or Play
App Signing) and the release artifact must be published over HTTPS.

Until that infrastructure is configured:

- updater source: IMPLEMENTED;
- remote signed release publication: BLOCKED;
- silent installation on ordinary Android: NOT_SUPPORTED_BY_PLATFORM.

## Data preservation

An in-place update with the same package/signing identity preserves app-private PocketPC
state.

P: PocketDrive is additionally external to the app-private C: volume, so user-facing
files remain separated from the application package lifecycle.

A signing-key migration that forces uninstall/reinstall would risk app-private C: state;
therefore PocketPC must settle its long-lived signing identity before broad
distribution.
