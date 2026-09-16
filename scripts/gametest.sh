#!/usr/bin/env bash
# Runs every GameTest. Fails unless the server reports that all required tests passed.
set -uo pipefail
cd "$(dirname "$0")/.."
export TMPDIR="${TMPDIR:-$HOME/.cache/tmp-villagercity}"
mkdir -p "$TMPDIR" build
log="build/gametest.log"
./gradlew --no-daemon -Dorg.gradle.workers.max=4 runGameTestServer >"$log" 2>&1
status=$?
/usr/bin/grep -a -E "required tests passed|tests? failed|GameTestAssertException|failed!" "$log" | tail -40
if [ "$status" -ne 0 ]; then
    echo "gametest: gradle exited $status (full log: $log)"
    tail -60 "$log"
    exit "$status"
fi
if ! /usr/bin/grep -a -q -E "All [0-9]+ required tests passed" "$log"; then
    echo "gametest: no passing summary found (full log: $log)"
    tail -60 "$log"
    exit 1
fi
