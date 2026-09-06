# PocketPC architecture — draft 0.16

## First physical path

preflight doctor -> strict build -> second preflight -> device install -> debug evidence runner -> evidence bundle -> full-chain verifier -> physical record -> final first-physical-test record.

The preflight layer prevents known local environment/device readiness failures from entering the evidence-producing stages.

PRoot/Linux remains a separate later branch.
