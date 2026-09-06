# Engineering rules

PocketPC is research software. Evidence labels are mandatory.

## Evidence ladder

1. DESIGN
2. IMPLEMENTED
3. STATICALLY VALIDATED
4. CI VALIDATED
5. DEVICE TESTED
6. BENCHMARKED

A higher level is never implied by a lower level.

## Performance rules

Never claim that:

- virtual VRAM creates physical memory;
- a vGPU creates compute capacity;
- peak FPS proves smoothness;
- PocketPC UI Choreographer data is the FPS of another app;
- a governor recommendation actually changes performance until wired to a controlled runtime;
- an optimization works without reproducible A/B measurement.

Prefer frame-time stability, 1%/0.1% lows, sustained thermals, power and compatibility over a single peak FPS number.

## Build rules

- Source presence is not a successful build.
- A failure before checkout is not a source compilation failure.
- An assembled APK is not device-tested.
- A device launch is not a benchmark.
- Every result should identify the exact commit.

## Security and permissions

The first application release must not require root. Storage access stays user-selected through SAF. Runtime isolation and third-party redistribution must be reviewed before bundling Linux, Wine, Box64, DXVK or VKD3D components.
