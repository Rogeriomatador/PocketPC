#!/usr/bin/env bash
set -euo pipefail

gradle --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
