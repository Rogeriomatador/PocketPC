# Benchmark plan

## Purpose

Separate real improvement from placebo, peak screenshots and thermal bursts.

## Record for every run

- commit SHA;
- APK/runtime versions;
- phone model and Android build;
- battery/charging state;
- display refresh/resolution;
- ambient/cooling conditions when relevant;
- workload and exact settings;
- warm-up and measurement duration.

## Metrics

When available:

- average FPS;
- median frame time;
- 1% and 0.1% low;
- stutter count/threshold;
- sustained performance over time;
- process memory;
- thermal status/headroom;
- battery/power proxy;
- startup/loading time;
- compatibility outcome.

## Method

Use repeated A/B runs with the same device/workload/settings. Compare a baseline and one controlled change at a time. Report regressions as well as wins.

For future Windows workloads, an established Android Windows compatibility stack may be used as a baseline when configuration can be matched fairly.

No optimization is marked BENCHMARKED from a single run.
