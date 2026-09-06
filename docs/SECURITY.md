# Security model — Alpha 13

Existing archive, guest-filesystem, supply-chain, attestation, build and evidence-bundle boundaries remain active.

## ADB device boundary

The device installer refuses emulators by default and requires exactly one authorized device unless an explicit serial is supplied.

Only SHA-256 of the device serial is persisted.

## APK boundary

The local build record is reverified before adb installation.

The script validates the local APK hash/bytes, installed package path, installed version and MainActivity launch.

Installed APK byte equality is attempted through adb pull and can be made mandatory.

## Classification boundary

Metadata-only installation and installed-APK-hash verification are different classifications.

Neither classification is DEVICE TESTED application behavior and neither can approve PRoot or enable Linux.
