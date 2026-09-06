# Device Evidence Chain — Alpha 14

scripts/verify-device-chain.py cross-verifies local build, device install and evidence bundle.

Alpha 14 adds optional strong gates:

--require-filesystem-pass
--require-native-host

The automated physical runner uses both.

Therefore a cryptographically consistent bundle from a device with broken link semantics or a failed PocketPC native host is rejected from the strong physical classification.

POCKETPC_DEVICE_CHAIN_OK still means internal identity consistency. The final physical record verifier is a separate last gate.
