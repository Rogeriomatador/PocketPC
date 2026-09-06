# CI status — Alpha 13

Hosted Android CI remains blocked before step execution in the latest observed runs.

Alpha 13 CI definitions add:

- Device install record verifier self-test;
- PowerShell parse check for both Windows build and device-install harnesses;
- Windows workflow parse/self-test coverage for the device installer.

A hosted runner cannot perform the real physical-device gate unless an actual authorized Android device is attached.

The intended real path is therefore local Windows build -> local ADB physical install -> in-app evidence bundle -> host full-chain verification.
