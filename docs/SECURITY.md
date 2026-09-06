# Security model — Alpha 11

## Existing boundaries

Archive security, guest filesystem safety, artifact quarantine and runtime attestation remain unchanged.

## Build identity

Unpinned local builds explicitly report LOCAL_UNPINNED.

CI builds use GITHUB_SHA when the workflow reaches the build.

PocketPC records signing-certificate SHA-256 values; it never reads or exports signing private keys.

## Bundle path security

EvidenceBundleCore accepts only relative controlled paths.

It rejects:

- absolute paths;
- backslashes;
- empty path components;
- . and .. components;
- duplicate paths;
- manifest path collision.

## Bundle integrity

Every payload entry is bound to byte length and SHA-256 in bundle-manifest.json.

device-evidence.json also keeps its own SHA-256 sidecar.

The bundle ZIP itself receives an internal sidecar before export, while the host verifier independently recomputes the exported ZIP SHA-256.

## Export boundary

CreateDocument/SAF gives the destination URI selected by the user.

PocketPC writes only to that returned destination and does not request broad filesystem access for bundle export.

## Verification boundary

The host verifier treats malformed JSON and malformed ZIP structures as clean failures instead of allowing parser crashes to be confused with successful verification.

A valid bundle still does not imply Linux execution, source approval or device-test PASS.
