# Execution model — Alpha 9

Status: **execution foundation implemented / runtime attestation added / executor disabled**

## Filesystem gates

1. STAGED_VERIFIED
2. INSTALLED_DATA
3. LINKS_PREPARED
4. guest entrypoint resolved

## Supply-chain gates

5. source archive verified
6. real artifact ELF audit
7. Android packaging contract resolved
8. license review
9. final ARTIFACTS.lock.json
10. device review

## Runtime attestation gates

11. embedded approval=true
12. policy asset digests match
13. native artifact bytes/hashes match
14. no unexpected substrate artifact

Only then can:

~~~text
prootReady=true
~~~

## Important separation

prootReady means the reviewed substrate is present and attested.

It does **not** mean Linux execution is enabled.

The executor remains blocked separately.

## Bind and environment policy

Existing Alpha 6 rules remain:

- structured argv;
- host bind allowlist;
- reserved guest paths;
- read-only bind fail-closed;
- minimal environment;
- explicit PROOT_LOADER.

## Supervisor

The one-shot process supervisor remains foundation only.

No PRoot command is wired to it in Alpha 9.

## Future first executor gate

Requires:

- prootReady on physical device;
- explicit executor approval;
- one allowlisted non-interactive guest command;
- bounded logs;
- timeout;
- deterministic cleanup;
- evidence attached to an exact commit/APK.
