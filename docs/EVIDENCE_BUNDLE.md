# Evidence Bundle — Alpha 11

Status: **IMPLEMENTED SOURCE / HOST FORMAT SMOKE / NOT DEVICE TESTED**

## Goal

Device evidence is useful only if it can be tied back to the exact PocketPC build and policy state that generated it.

Alpha 11 creates a self-contained ZIP evidence bundle.

## Bundle layout

Typical denied-substrate bundle:

~~~text
bundle-info.json
bundle-manifest.json

evidence/
  device-evidence.json
  device-evidence.sha256

identity/
  build-identity.json

policy/
  proot-substrate-approval.json
  proot/
    LOCK.json
    ARTIFACT_CONTRACT.json
~~~

When a future final artifact lock exists, the bundle also includes:

~~~text
policy/proot/ARTIFACTS.lock.json
~~~

## Bundle manifest

bundle-manifest.json lists each payload entry with:

- path;
- byte length;
- SHA-256.

The manifest never hashes itself.

Bundle paths reject:

- absolute paths;
- backslashes;
- empty components;
- dot components;
- dot-dot traversal;
- duplicate paths.

## Build revision

BuildConfig receives the revision from:

1. GITHUB_SHA;
2. POCKETPC_SOURCE_REVISION;
3. otherwise LOCAL_UNPINNED.

Only a 40-character hexadecimal revision is marked pinned.

This makes local experimental APKs visibly different from reproducibly identified CI builds.

## APK signature identity

BuildIdentityCollector uses the Android package signing information.

For Android API 28+, SigningInfo is used, accounting for signing history and multiple signers.

For API 26–27, the deprecated signatures field is used only as the backwards-compatible fallback.

The evidence records SHA-256 values of the signing certificate bytes, not private keys.

## Export

System -> Teste do dispositivo -> Executar teste.

When collection succeeds, EvidenceBundleManager creates the bundle internally.

Exportar bundle uses CreateDocument with application/zip so the user chooses the destination.

## Host verifier

verify-device-evidence-bundle.py fails closed on:

- invalid ZIP;
- unsafe paths;
- duplicate paths;
- missing required files;
- manifest mismatch;
- byte mismatch;
- SHA-256 mismatch;
- invalid evidence JSON;
- evidence sidecar mismatch;
- identity mismatch;
- malformed pinned revision;
- malformed certificate digest;
- invalid basic approval state.

Passing this verifier means the bundle is internally consistent.

It does not establish that the Android test itself passed or that PRoot is executable.

## Exact-revision review

For a build expected to come from commit ABC..., review should run:

~~~text
verify-device-evidence-bundle.py bundle.zip --expected-revision ABC...
~~~

A LOCAL_UNPINNED bundle is still useful for diagnostics but should not be elevated to reproducible device evidence for a specific repository commit.
