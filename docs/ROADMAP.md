# Roadmap

## 0.1.0-alpha11 — evidence bundle & build identity

- Alpha 10 physical-device harness;
- source revision embedded into BuildConfig;
- LOCAL_UNPINNED fallback;
- APK signing-certificate SHA-256;
- package/version/installer identity;
- evidence JSON schema v2;
- deterministic payload manifest;
- policy snapshots;
- evidence ZIP;
- SAF export;
- host bundle verifier;
- tamper-detection self-test;
- CI APK SHA-256/build record definition.

## Next practical gate

Produce an installable APK from a functioning Android build environment.

Then:

1. record APK SHA-256;
2. install APK on ARM64 Android;
3. run Device Evidence Harness;
4. export Alpha 11 evidence bundle;
5. run host verifier with expected commit;
6. inspect filesystem/native-host/substrate results.

## Supply-chain work remains separate

- source archive audit;
- real PRoot quarantine build;
- talloc/Android dynamic-link resolution;
- license review;
- final artifact lock;
- approved substrate attestation.

## Linux executor

Still disabled after all Alpha 11 work.
