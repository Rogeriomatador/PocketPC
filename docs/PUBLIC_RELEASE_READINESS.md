# PocketPC public release readiness

PocketPC separates two kinds of publication:

- **Home Test**: signed development APKs used to advance and physically test individual
  stages. A Home Test release does not claim that Roblox works.
- **Public gameplay-ready release**: blocked until one exact source revision has immutable
  physical evidence for the complete chain.

The stable publisher runs `scripts/verify-public-release-readiness.py`. Its tracked
record starts as `BLOCKED_NOT_EXECUTED` and must remain blocked until all of these are
observed on the same exact revision:

1. APK build and software tests;
2. PRoot plus prepared rootfs execution;
3. Box64 executing an x86-64 program;
4. Wine executing Win64;
5. a Wine window presented inside PocketPC;
6. continuous Vulkan/DXVK presentation;
7. Roblox Player process and rendered window;
8. a real Roblox server session;
9. external network;
10. gameplay input;
11. audio;
12. at least five stable minutes without an observed crash.

The readiness JSON is only an index. It must point to an immutable GitHub Release
evidence asset and bind its SHA-256 and exact source revision. Metadata alone is not
physical evidence.

Until the verifier prints `PUBLIC_RELEASE_READINESS_OK`, public stable publication is
**BLOCKED**. Home Test publication remains independent so development can continue.
