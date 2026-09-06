# PocketPC architecture — draft 0.17

## First physical path

public wrapper -> Alpha 16 physical-test core -> success evidence OR automatic failure triage.

Successful path:

preflight -> strict build -> preflight -> install -> debug evidence -> bundle -> cross-verification -> physical record -> final record.

Failure path:

original exception -> triage collector -> hash-bound diagnostic pack -> original exception rethrown.

PRoot/Linux remains a separate later branch.
