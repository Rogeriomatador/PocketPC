# PocketPC — 0.1.0-alpha11

PocketPC is an experimental Android desktop/runtime project aimed at turning a phone into a desktop workstation while preserving strict evidence labels.

## Alpha 11 — Evidence Bundle & Build Identity

Alpha 11 builds on the Alpha 10 Device Evidence Harness and makes physical-device evidence exportable and traceable to a concrete app build.

After running the device test, PocketPC can create and export an audit bundle through Android SAF.

The bundle contains:

- device evidence JSON;
- evidence SHA-256 sidecar;
- build identity JSON;
- source revision identity;
- APK signing-certificate SHA-256 values;
- installer package when available;
- PRoot approval manifest snapshot;
- PRoot source lock snapshot;
- PRoot artifact contract snapshot;
- final artifact lock snapshot when one exists;
- bundle-info metadata;
- bundle-manifest.json containing SHA-256 and byte length for every payload entry.

## Build identity

CI builds read GITHUB_SHA and embed that revision into BuildConfig.

A local build without a supplied revision records:

~~~text
LOCAL_UNPINNED
~~~

PocketPC never invents a commit for an unpinned local build.

Build identity records:

- package name;
- version name/code;
- source revision;
- whether that revision is pinned;
- debug/release state;
- APK signing certificate SHA-256 list;
- installer package when available.

## Evidence bundle export

The System panel flow becomes:

~~~text
Executar teste
    ↓
device-evidence.json
    ↓
SHA-256 sidecar
    ↓
build identity + policy snapshots
    ↓
bundle-manifest.json
    ↓
PocketPC evidence ZIP
    ↓
Exportar bundle
~~~

The export uses Android CreateDocument/SAF and does not request broad shared-storage access.

## Host verification

scripts/verify-device-evidence-bundle.py verifies:

- ZIP entry safety;
- no duplicate paths;
- manifest/payload entry-set equality;
- byte counts;
- SHA-256 for every payload;
- evidence sidecar;
- evidence/build-identity consistency;
- revision pinning;
- signing-certificate digest format;
- approval manifest basic state.

It can also require an exact revision:

~~~text
python3 scripts/verify-device-evidence-bundle.py \
  PocketPC-...-evidence.zip \
  --expected-revision <40-char-git-sha>
~~~

A separate self-test creates a valid synthetic bundle and a tampered bundle.

## APK CI identity

When Android CI eventually runs successfully, it is configured to upload:

- app-debug.apk;
- app-debug.apk.sha256;
- app-debug.build.txt.

The build record includes GITHUB_SHA, version and workflow-run identity.

## Evidence boundary

Alpha 11 source is IMPLEMENTED.

The bundle/hash format received a local smoke of its core integrity relationships, including tamper detection.

Android compilation and physical-device export are still not validated because hosted Actions remains blocked before step 1.

## Current Linux state

- approval=false;
- no ARTIFACTS.lock.json;
- no PRoot binary bundled;
- prootReady=false;
- Linux executor disabled.
