# Device Evidence Harness — Alpha 11

Status: **IMPLEMENTED SOURCE / NOT YET RUN ON PHYSICAL DEVICE**

## Physical filesystem test

The Alpha 10 checks remain:

- relative symlink;
- absolute symlink;
- hardlink;
- Files.isSameFile;
- NOFOLLOW cleanup;
- external target preservation.

The test stays inside app-private temporary storage and never runs PRoot.

## Alpha 11 additions

The evidence JSON schema advances to v2 and embeds Build Identity.

The report now carries:

- package/version identity;
- source revision and pinning status;
- APK signing-certificate hashes;
- installer package;
- device identity/capabilities;
- filesystem results;
- native host information;
- substrate approval/attestation state.

## Persistence

Internal files:

~~~text
device-evidence-latest.json
device-evidence-latest.sha256
pocketpc-evidence-bundle-latest.zip
pocketpc-evidence-bundle-latest.sha256
~~~

The bundle can be exported from the System UI through SAF.

## Evidence rule

A future DEVICE TESTED classification should reference at minimum:

- exact repository commit;
- APK SHA-256;
- APK signing identity;
- exported evidence bundle SHA-256;
- device model/API/ABI;
- relevant PASS/FAIL fields.

A screenshot alone should not replace the structured bundle when the bundle is available.
