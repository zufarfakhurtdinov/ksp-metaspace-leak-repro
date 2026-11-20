#!/usr/bin/env bash
# Reproduces the KSP2 metaspace leak in one Gradle daemon.
#
#   ./repro.sh [builds] [mode]
#
# mode=change (default): before each KSP rerun, change a file on the build script classpath, so Gradle creates a new
#                        classloader for the project's plugins.
# mode=same:             rerun KSP without that change (control, should stay flat).
set -u

BUILDS=${1:-20}
MODE=${2:-change}
LOG=build/repro.log

cd "$(dirname "$0")"
mkdir -p build

for ((build = 1; build <= BUILDS; build++)); do
  if [[ $MODE == change ]]; then
    echo "$build" > generation/value
  fi

  if ./gradlew --quiet kspKotlin --rerun report > "$LOG" 2>&1; then
    echo "build $build: $(grep 'metaspace:' "$LOG")"
  else
    echo "build $build failed, full output in $LOG:"
    grep 'Metaspace' "$LOG" | head -1
    break
  fi
done

echo 0 > generation/value
