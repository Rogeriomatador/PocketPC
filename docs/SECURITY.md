# Security model — Alpha 10

## Existing boundaries

Archive integrity, link-free extraction, transactional guest-link preparation, artifact quarantine and runtime attestation remain unchanged.

## Device self-test boundary

The new Device Evidence Harness:

- is explicit user-triggered;
- runs only in app-private temporary storage;
- does not use SAF/shared storage;
- does not invoke PRoot;
- does not invoke the future Linux executor;
- does not require root;
- uses NOFOLLOW deletion;
- removes temporary test trees.

## External-target test

The harness deliberately creates an app-private symlink pointing at a sibling app-private test directory.

The root tree is deleted with NOFOLLOW and the sibling file must survive.

This is a safety proof for cleanup behavior, not permission expansion.

## Evidence integrity

The report is written transactionally and receives a SHA-256 sidecar.

The sidecar is not a digital signature. It detects byte changes but does not establish authorship.

## Approval boundary

Device filesystem PASS cannot set approved=true.

Artifact approval still requires the full source/artifact/license/device chain.

## Execution boundary

The Linux executor remains disabled independently of the device harness.
