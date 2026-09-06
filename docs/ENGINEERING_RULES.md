# Engineering rules

PocketPC uses explicit evidence labels so design ideas never become fake implementation claims.

## Evidence ladder

1. **DESIGN** — documented architecture or proposal only.
2. **IMPLEMENTED** — source exists in the repository.
3. **STATICALLY REVIEWED** — source was inspected for obvious structural issues.
4. **CI VALIDATED** — automated build/tests passed for the exact commit.
5. **DEVICE TESTED** — the exact APK was exercised on real Android hardware.
6. **BENCHMARKED** — reproducible measurements exist with device, build, workload and methodology recorded.

A higher label must not be inferred from a lower one.

## Performance claims

Never claim that:
- virtual VRAM creates physical memory;
- a virtual GPU creates compute capacity that the SoC does not have;
- a higher average FPS automatically means smoother gameplay;
- an optimization works until a reproducible A/B measurement demonstrates it.

Prefer frame-time stability, 1% lows, sustained performance, thermal behavior and power use over peak FPS alone.

## Third-party software

Compatibility work must respect upstream licenses and redistribution terms. Initial app releases must not depend on root and must not inject into arbitrary third-party Android game processes.
