# Device Evidence Harness — Alpha 10

Status: **IMPLEMENTED SOURCE / NOT YET RUN ON PHYSICAL DEVICE**

## Purpose

Several PocketPC gates cannot be proven by JVM tests or source inspection:

- Android app-private symlink behavior;
- hardlink support on the device filesystem;
- NOFOLLOW cleanup behavior;
- nativeLibraryDir extraction/permissions;
- actual runtime substrate state.

Alpha 10 adds an in-app evidence harness for those questions.

## Safety boundary

The filesystem test:

- uses context.cacheDir only;
- creates uniquely named temporary directories;
- creates a separate external test target under the same app cache;
- never touches shared/user storage;
- never runs PRoot;
- never requests root;
- removes its test data afterward.

## Filesystem checks

### Relative symlink

Creates a Linux-like layout:

~~~text
guest/usr/bin/sh
guest/bin -> usr/bin
~~~

Then verifies the link node and target text.

### Absolute symlink

Creates a symlink to a second app-private temporary directory.

This is used only to prove that NOFOLLOW cleanup does not traverse the target.

### Hardlink

Creates a regular file and a hardlink, then requires:

~~~text
Files.isSameFile(alias, target) == true
~~~

### NOFOLLOW cleanup

Deletes the root test tree using SafeTreeOps.deleteNoFollow.

It then verifies the external target file still exists with unchanged content.

## Evidence report

DeviceEvidenceCollector stores:

- timestamp UTC;
- PocketPC version;
- Build manufacturer/model/API/ABIs;
- each filesystem capability result and detail;
- Native Runtime Host state;
- Vulkan probe string;
- nativeLibraryDir;
- substrate state;
- approval/attestation booleans;
- substrate components;
- approval errors.

## Integrity sidecar

After the JSON is promoted, PocketPC computes SHA-256 and writes:

~~~text
device-evidence-latest.sha256
~~~

This lets later analysis refer to the exact evidence bytes.

## Evidence classification

Source presence: IMPLEMENTED.

Host/JVM test: SOFTWARE TEST only.

Physical phone run: DEVICE TEST candidate, but only after recording exact APK/commit and reviewing the resulting report.

A passing filesystem harness does not validate PRoot execution.
