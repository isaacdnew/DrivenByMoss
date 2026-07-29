#!/usr/bin/env bash
#
# Compile check only: builds the extension (through the `package` phase) but does NOT install it into
# Bitwig. Prints a single BUILD SUCCESS / BUILD FAILURE line; on failure it echoes the compiler errors
# and the path to the full log, and exits non-zero. Use this while iterating on the code.
#
# To build AND install into Bitwig, use install.sh instead.

set -uo pipefail
cd "$(dirname "$0")"

# Homebrew's stable "opt" symlink survives OpenJDK patch updates (unlike a pinned Cellar path).
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"

log="$(mktemp -t dbm-build)"
if mvn -o clean package >"$log" 2>&1; then
    echo "BUILD SUCCESS"
    rm -f "$log"
else
    echo "BUILD FAILURE:"
    grep -E "\[ERROR\]|\.java:\[|BUILD FAILURE" "$log" | head -40
    echo "(full log: $log)"
    exit 1
fi
