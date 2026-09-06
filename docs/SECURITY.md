# Security model — Alpha 14

Existing archive, guest-filesystem, substrate, build, install and evidence-bundle protections remain.

## Debug automation boundary

DebugEvidenceActivity exists only in src/debug.

It runs PocketPC's own deterministic evidence probes and writes only app-specific evidence output. It does not execute arbitrary shell commands, PRoot, or guest code.

## Strong physical classification

The final physical classification requires both critical filesystem PASS and Native Runtime Host loaded.

The final record is hash-bound to build record, install record, automation result, device evidence and bundle, and receives its own sidecar.

verify-physical-validation-record.py independently recomputes those relationships.
