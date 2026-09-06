# Security model — Alpha 16

Alpha 16 adds an environment/device readiness boundary before build/install.

The doctor is non-destructive by default. It records only SHA-256 of the ADB serial, not the raw serial.

Missing SDK components are FAIL unless explicitly treated as repairable warnings by the one-command harness when installation was requested.

The second doctor pass requires the repaired environment to be ready before ADB physical validation continues.

All earlier build, install, evidence, filesystem, native-host and PRoot separation gates remain unchanged.
