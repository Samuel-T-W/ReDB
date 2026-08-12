#!/usr/bin/env bash
# Idempotent Cloud Agent install: prepare the ReDB Java engine and web showcase.
# Safe to run repeatedly. Assumes JDK 21, Maven 3.9.x, and Node are already on
# the base image/snapshot.
set -euo pipefail

cd "$(dirname "$0")/.."

# 1. Provide the top-level CSVs the engine and Java tests read from data/.
#    The real IMDB set is fetched from a GitHub Release in CI; for local/agent
#    development we use the committed synthetic fixtures, which share the same
#    schema and file names.
for f in title workedon name; do
  cp -f "data/fixtures/synthetic/${f}.csv" "data/${f}.csv"
done

# 2. Prime the Java build: download dependencies and compile main + test sources.
mvn -B -q -DskipTests test-compile

# 3. Install web showcase dependencies from the lockfile.
(cd web && npm ci)

echo "ReDB install complete."
