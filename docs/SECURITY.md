# Security model — Alpha 17

Alpha 17 adds diagnostics without weakening any previous evidence gate.

The first-physical-test wrapper always rethrows the original failure after attempting triage.

The triage collector stores SHA-256 of the device serial rather than the raw serial.

Logcat is process-scoped when possible and crash-error-only as fallback.

Every captured diagnostic file is hash-bound by triage-record.json and its sidecar.

PRoot approval and Linux execution remain independent and disabled.
