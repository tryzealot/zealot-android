#!/usr/bin/env bash
set -euo pipefail

log=$(mktemp)
trap 'rm -- "$log"' EXIT

if ./gradlew --console=plain "$@" 2>&1 | tee "$log"; then
    exit 0
else
    status=$?
fi

# Retry only a dependency request rejected by Maven Central's rate limit.
# Gradle already makes short HTTP retries, so give the repository time to recover.
if ! awk '
    /Received status code 429 from server/ && central_request { limited = 1 }
    { central_request = /Could not (GET|HEAD) .https:\/\/repo\.maven\.apache\.org\/maven2\// }
    END { exit !limited }
' "$log"; then
    exit "$status"
fi

echo "Maven Central returned HTTP 429. Retrying this Gradle step once in 60 seconds."
sleep 60
./gradlew --console=plain "$@"
