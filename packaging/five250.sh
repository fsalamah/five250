#!/usr/bin/env bash
# Self-contained launcher for the jlink'd distribution (target/dist after "mvn package") -
# uses the bundled runtime/ next to this script, never whatever Java (if any) happens to be on
# this machine's PATH. Copy the whole dist folder anywhere on macOS/Linux and run this, or put
# it on PATH under the name "five250" (matching bin/five250's role in the source tree).
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/runtime/bin/java" -jar "$DIR/five250.jar" "$@"
