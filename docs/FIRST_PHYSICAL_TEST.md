# First Physical Test — Alpha 16

The Alpha 15 one-command chain remains unchanged in evidence requirements.

Alpha 16 adds two preflight doctor passes around the strict build step.

Sequence:

preflight-before -> strict build -> preflight-after -> install -> debug evidence runner -> bundle verification -> full device chain -> physical record -> final record.

Only the complete sequence may print POCKETPC_FIRST_PHYSICAL_TEST_OK.
