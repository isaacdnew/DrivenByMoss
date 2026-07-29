#!/usr/bin/env bash
#
# Build AND install into Bitwig: builds through the `install` phase, which writes the extension straight
# into Bitwig's user Extensions folder (no manual copy). Prints INSTALL SUCCESS / INSTALL FAILURE; on
# failure it echoes the compiler errors and the path to the full log, and exits non-zero.
#
# For a plain compile check that does NOT touch Bitwig, use build.sh instead.

set -uo pipefail
cd "$(dirname "$0")"

# Homebrew's stable "opt" symlink survives OpenJDK patch updates (unlike a pinned Cellar path).
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"

EXT_DIR="$HOME/Documents/Bitwig Studio/Extensions"

log="$(mktemp -t dbm-install)"
if mvn -o clean install -Dbitwig.extension.directory="$EXT_DIR" >"$log" 2>&1; then
    echo "INSTALL SUCCESS -> $EXT_DIR/DrivenByMoss.bwextension"
    rm -f "$log"
else
    echo "INSTALL FAILURE:"
    grep -E "\[ERROR\]|\.java:\[|BUILD FAILURE" "$log" | head -40
    echo "(full log: $log)"
    exit 1
fi
