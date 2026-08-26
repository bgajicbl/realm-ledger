#!/usr/bin/env bash
# Block framework imports from leaking into the domain.
#
# This hook exists because of a specific, repeated failure. Told to "add a repository
# lookup", an agent will annotate a class in application/ with @Service and inject a
# Spring repository, because that is what the surrounding ecosystem looks like in its
# training data. The result compiles, the tests pass, and the architecture is quietly
# gone: core/ can no longer be tested without a Spring context, and the fast self-check
# stops working.
#
# Saying "do not do that" in CLAUDE.md worked about four times out of five. This works
# every time, and it works for humans too.
#
# Wired as a PostToolUse hook on Write|Edit. Exit 2 blocks and returns stderr to the
# agent, so the next thing it sees is the reason rather than a stale success.

set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

GUARDED="src/main/java/io/realmledger/core src/main/java/io/realmledger/port src/main/java/io/realmledger/application"
FORBIDDEN='^import (org\.springframework|jakarta\.persistence|jakarta\.validation|com\.fasterxml\.jackson|io\.lettuce|org\.hibernate)'

violations=""
for dir in $GUARDED; do
  [ -d "$dir" ] || continue
  found=$(grep -rEn "$FORBIDDEN" "$dir" 2>/dev/null || true)
  [ -n "$found" ] && violations="${violations}${found}\n"
done

if [ -n "$violations" ]; then
  {
    echo "LAYERING VIOLATION: framework types imported into the domain."
    echo
    printf "%b" "$violations"
    echo "core/, port/ and application/ must compile and test without Spring, JPA or"
    echo "Jackson on the classpath. Put the framework code in adapter/ and talk to it"
    echo "through a port interface. See docs/ARCHITECTURE.md."
  } >&2
  exit 2
fi

exit 0
