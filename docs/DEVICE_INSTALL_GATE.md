# Device Install Gate — Alpha 13

Status: IMPLEMENTED SOURCE / PHYSICAL EXECUTION PENDING

## Purpose

The first phone test should verify the exact APK that came from the build evidence chain, not an arbitrary file copied to the device.

## Preconditions

- Alpha 12 local build directory exists;
- local-build-record.json verifies;
- Python 3 is functional;
- Android Platform Tools/adb is available;
- one authorized physical Android device is connected;
- arm64-v8a is present in the device ABI list.

## Device selection

An explicit -DeviceSerial may be supplied. Otherwise the script accepts exactly one authorized non-emulator device.

The raw serial is never written to the install record. SHA-256(serial) is stored instead.

## Installation

The script uses adb install -r -t --no-incremental for a complete reinstall/test-APK path.

After installation it confirms pm path, reads installed versionName/versionCode and launches MainActivity with am start -W -S.

## Installed APK hash

The gate attempts adb pull of the installed base APK. If the pulled bytes match the local APK, classification becomes DEVICE_INSTALL_APK_HASH_VERIFIED.

If pull is unavailable but package/version/launch checks pass, classification is DEVICE_INSTALL_METADATA_VERIFIED.

-RequireInstalledApkHash converts pull unavailability into a hard failure.

## Output

device-install/device-install-record.json

device-install/device-install-record.json.sha256

device-install/activity-launch.txt

Passing this gate proves installation/launch identity only. It does not prove Device Evidence Harness, PRoot or Linux execution.
