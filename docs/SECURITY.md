# Security model — Alpha 15

Alpha 15 does not relax any earlier gate.

The one-command harness refuses dirty source and always invokes the strict builder. It accepts the physical test only after the final record verifier rechecks hashes linking source build evidence and physical evidence.

The debug-only evidence Activity remains confined to src/debug.

POCKETPC_FIRST_PHYSICAL_TEST_OK is intentionally unrelated to PRoot approval or Linux execution.
