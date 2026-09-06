# Runtime artifact attestation — Alpha 9

Status: **IMPLEMENTED SOURCE / CURRENT APPROVAL DENIED**

## Threat model

PocketPC must not treat any of these as sufficient evidence:

- a file named libproot.so exists;
- an ELF audit passed;
- a source build succeeded;
- a candidate JSON was generated;
- nativeLibraryDir contains all expected names.

A file can be stale, replaced, repackaged, renamed or mixed with an unreviewed dependency.

## Approval manifest

The APK contains:

~~~text
app/src/main/assets/proot-substrate-approval.json
~~~

Current state:

~~~json
{
  "status": "NOT_APPROVED",
  "approved": false,
  "artifacts": []
}
~~~

When approved=false:

- policy digests must be empty;
- artifacts must be empty;
- every review gate must be false.

This prevents partial evidence from being presented as an approval.

## Future approved manifest

An approval=true manifest must bind to:

- sourceLockSha256;
- artifactContractSha256;
- artifactLockSha256.

It must contain exactly:

- proot;
- loader64;
- libandroid-shmem;
- libtalloc.

Each artifact records:

- role;
- Android packaging filename;
- SHA-256;
- bytes;
- whether executable permission is required.

## Policy assets

Gradle packages third_party/ as an asset source.

Runtime digest verification reads:

~~~text
proot/LOCK.json
proot/ARTIFACT_CONTRACT.json
proot/ARTIFACTS.lock.json
~~~

For approved=true, the actual asset SHA-256 must exactly match the approval manifest.

ARTIFACTS.lock.json does not exist yet, so approved=true cannot pass today.

## Android naming rule

Approved aliases must end in .so.

Required fixed aliases:

- libproot.so
- libproot_loader.so
- libandroid-shmem.so

The talloc approved alias must also be a lib*.so name.

A raw build artifact such as libtalloc.so.2 is evidence input, not an APK approval alias.

## Native artifact attestation

After policy binding passes, PocketPC verifies nativeLibraryDir:

- file exists;
- canonical parent is exactly nativeLibraryDir;
- file is readable;
- executable artifacts have execute permission;
- exact byte length;
- exact SHA-256;
- no unexpected sensitive PRoot/talloc/shmem file exists.

Only then may the attestation state become:

~~~text
SUBSTRATE_ARTIFACTS_ATTESTED
~~~

## prootReady

prootReady requires:

- PocketPC native host present;
- approval manifest approved;
- policy digests verified;
- all artifact hashes verified.

The Linux executor is still an independent later gate.

## CI policy

scripts/verify-proot-approval.py mirrors the fail-closed approval policy.

approved=true requires:

- ARTIFACTS.lock.json;
- lock status APPROVED;
- promotion.approved=true;
- no Android packaging blockers;
- no unreviewed dependencies;
- matching source/contract/artifact lock hashes;
- all review flags true;
- exact artifact role set;
- Android-compatible lib*.so aliases.

CI also rejects substrate binaries from app/src and third_party until the explicit future approval transition is designed.
