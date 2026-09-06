# Local Windows Build Harness — Alpha 14

The local builder remains the source-to-APK producer.

Strict Python policy mode now also runs the full device-chain verifier self-test and physical-validation-record self-test before compiling Android.

After a successful clean build, validate-device-windows.ps1 is the preferred next command because it includes the install and automated evidence gates.
