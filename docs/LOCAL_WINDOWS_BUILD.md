# Local Windows Build Harness — Alpha 13

Status: IMPLEMENTED SOURCE / WINDOWS EXECUTION PENDING

The Alpha 12 builder remains the source-to-APK producer and now emits the Alpha 13 app identity from android-build-lock.json.

Clean trees embed the exact commit. Dirty builds are refused by default and can only proceed as LOCAL_UNPINNED when explicitly allowed.

The builder continues to run policy checks, unit tests, lint, assembleDebug, APK structure checks, PRoot payload rejection, apksigner verification and APK SHA-256 recording.

Alpha 13 adds test-device-install-record-verifier.py to the strict Python policy set so the downstream ADB install record format is checked before local compilation.

After a successful build, scripts/install-device-windows.ps1 is the next gate.
