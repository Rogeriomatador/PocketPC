# Security model — Alpha 9

## Archive boundary

Strict staged archive integrity, bounded extraction and path validation remain in force.

## Guest filesystem boundary

Links are created only after extraction and validation. Hardlinks are inode verified. Cleanup uses NOFOLLOW traversal.

## Supply-chain boundary

- exact source pin;
- archive hash audit;
- artifact quarantine;
- ELF architecture/dependency audit;
- Android packaging blocker detection;
- no automatic artifact promotion.

## Approval boundary

Alpha 9 adds a separate approval manifest.

A denied approval must contain no stale hashes, artifacts or completed review flags.

A future approved manifest must be cryptographically tied by SHA-256 to:

- source lock;
- artifact contract;
- final artifact lock.

## Native runtime boundary

Even an approved manifest is insufficient by itself.

PocketPC verifies the actual files in nativeLibraryDir:

- exact names;
- exact size;
- exact SHA-256;
- required execute permission;
- canonical containment;
- absence of extra sensitive substrate artifacts.

## Android packaging boundary

Approved aliases must conform to lib*.so packaging names.

Versioned talloc SONAME/DT_NEEDED is treated as a blocker until the build/link contract is resolved.

## Execution boundary

prootReady still does not enable the Linux executor.

The executor remains a separate future control after supply-chain and device evidence.

## CI

CI checks the denied/approved manifest policy and rejects PRoot-related binaries under both app/src and third_party while the current project remains unapproved.
