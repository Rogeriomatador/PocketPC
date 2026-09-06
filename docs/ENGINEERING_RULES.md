# PocketPC engineering rules

PocketPC uses explicit evidence labels so design ideas never become fake implementation claims.

## Evidence ladder

1. **DESIGN** — architecture or proposal only.
2. **IMPLEMENTED** — source exists in the repository.
3. **STATICALLY REVIEWED** — source was inspected for obvious structural issues.
4. **CI VALIDATED** — automated build/tests passed for the exact commit.
5. **DEVICE TESTED** — the exact APK was exercised on physical Android hardware.
6. **BENCHMARKED** — reproducible measurements exist with device, build, workload and methodology recorded.

A higher label must never be inferred from a lower one.

## Performance claims

Never claim that:
- virtual VRAM creates physical memory;
- a virtual GPU creates compute capacity the SoC does not have;
- higher average FPS automatically means smoother gameplay;
- an optimization works before a reproducible A/B measurement demonstrates it.

Prefer frame-time stability, 1% lows, sustained performance, thermal behavior, memory pressure and power use over peak FPS alone.

## Android security model

The normal PocketPC APK must remain useful without root. Android sandbox and Storage Access Framework boundaries are treated as product constraints, not obstacles to bypass.

A future system/ROM edition may have deeper privileges, but it must be a separately documented target.

## Third-party software

Compatibility work must respect upstream licenses and redistribution terms. Wine, Box64, DXVK, VKD3D, Mesa/Turnip or any other external component is **PLANNED** until the repository contains a compliant integration and evidence for it.
