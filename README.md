# PocketPC — 0.1.0-alpha9

PocketPC is an experimental Android desktop/runtime project aimed at turning a phone into a desktop workstation while preserving strict evidence labels.

## Evidence ladder

- **DESIGN**
- **IMPLEMENTED**
- **STATICALLY VALIDATED**
- **CI VALIDATED**
- **DEVICE TESTED**
- **BENCHMARKED**

A higher label is never inferred from a lower one.

## Alpha 9 — runtime artifact attestation

Alpha 9 keeps all Alpha 8 quarantine/ELF gates and adds a runtime approval system that does not trust file presence.

The future PRoot substrate now needs all of the following before prootReady can become true:

1. an embedded approval manifest with approved=true;
2. completed source/ELF/license/device review flags;
3. SHA-256 bindings to the exact source lock;
4. SHA-256 binding to the exact artifact contract;
5. SHA-256 binding to a final ARTIFACTS.lock.json;
6. exactly four approved substrate roles;
7. Android-packagable lib<name>.so aliases;
8. exact file size match in nativeLibraryDir;
9. exact SHA-256 match for every native artifact;
10. no extra sensitive PRoot/talloc/shmem artifact in nativeLibraryDir.

Current approval remains:

~~~text
approved=false
status=NOT_APPROVED
~~~

Therefore Linux execution remains blocked.

## Runtime gate

~~~text
SOURCE LOCK
    │
ARTIFACT CONTRACT
    │
ARTIFACTS.lock.json
    │
    ├── SHA-256 binding ──┐
    │                     │
approval manifest         │
    │                     │
    └──── policy digest verification
              ↓
nativeLibraryDir files
              ↓
bytes + SHA-256 + permissions
              ↓
extra sensitive file rejection
              ↓
SUBSTRATE_ARTIFACTS_ATTESTED
              ↓
[executor still separate]
~~~

## Android packaging constraint

Android Package Manager expects native APK libraries in the form:

~~~text
lib/<abi>/lib<name>.so
~~~

For that reason Alpha 9 rejects a future approval alias such as libtalloc.so.2. A real build that produces a versioned talloc SONAME/DT_NEEDED must be resolved by an audited rebuild/link strategy before APK promotion.

## Policy assets

The APK includes the policy directory as assets:

- proot/LOCK.json
- proot/ARTIFACT_CONTRACT.json
- future proot/ARTIFACTS.lock.json

The approval manifest stores the SHA-256 of those exact files. A policy edit without a matching approval update invalidates the substrate.

## Current evidence

Alpha 7 guest-filesystem core: STATICALLY VALIDATED.

Alpha 8 ELF-policy self-test: STATICALLY VALIDATED.

Alpha 9 attestation source/tests are IMPLEMENTED source. Android/JVM CI validation remains blocked by the hosted runner problem.

## Version

- versionCode: 9
- versionName: 0.1.0-alpha9
- compile/target SDK: 37
- min SDK: 26
- NDK: 29.0.14206865
- CMake: 3.22.1

## CI status

Android CI #18 and PRoot Source Audit #2 both failed before step 1 with steps=null and logs_url=null.

Classification remains **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**.

## Next gates

1. runner allocation;
2. source archive hash audit;
3. real ARM64 quarantine build;
4. resolve any Android packaging blockers;
5. final license review;
6. create ARTIFACTS.lock.json from reviewed real artifacts;
7. physical-device artifact/link test;
8. explicit approval manifest commit;
9. only after attestation succeeds may the separate executor gate be considered.
