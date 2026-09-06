# Security model — Alpha 12

Archive validation, guest-link safety, NOFOLLOW cleanup, artifact quarantine, runtime attestation and evidence-bundle integrity remain active.

## Local build supply chain

Gradle is accepted only after the pinned distribution SHA-256 matches.

Dirty source is refused by default. Explicit dirty builds embed LOCAL_UNPINNED.

verify-android-build-lock.py detects configuration drift.

The local builder rejects unapproved PRoot/talloc/shmem payload, verifies APK structure and captures signing-certificate hashes.

The APK receives a SHA-256 sidecar and structured build record; verify-local-build-record.py recomputes and checks them.

Python policy checks may be skipped only with an explicit lower-confidence classification; strict mode requires them.
