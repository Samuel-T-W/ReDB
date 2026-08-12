#!/usr/bin/env bash
# Idempotent Cloud Agent install: prepare the ReDB Java engine and web showcase.
# Safe to run repeatedly. Assumes the base image provides JDK 21 and Node; this
# script bootstraps Maven itself because the JUnit 5 test suite needs a modern
# Surefire (Maven 3.9.x), which the distro package does not provide.
set -euo pipefail

cd "$(dirname "$0")/.."

MAVEN_VERSION="3.9.9"
MAVEN_HOME="/opt/apache-maven-${MAVEN_VERSION}"

have_modern_maven() {
  command -v mvn >/dev/null 2>&1 || return 1
  # Require >= 3.9 so Surefire defaults to a JUnit 5-capable version.
  local v major minor
  v="$(mvn -version 2>/dev/null | sed -n 's/^Apache Maven \([0-9][0-9.]*\).*/\1/p')"
  [ -n "$v" ] || return 1
  major="${v%%.*}"
  minor="$(printf '%s' "$v" | cut -d. -f2)"
  [ "$major" -gt 3 ] || { [ "$major" -eq 3 ] && [ "$minor" -ge 9 ]; }
}

# 1. Ensure a modern Maven is available.
if ! have_modern_maven; then
  if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
    tmp="$(mktemp -d)"
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o "${tmp}/maven.tar.gz"
    sudo tar -xzf "${tmp}/maven.tar.gz" -C /opt
    rm -rf "${tmp}"
  fi
  sudo ln -sfn "${MAVEN_HOME}/bin/mvn" /usr/local/bin/mvn
  hash -r
fi
mvn -version | head -1

# 2. Provide the top-level CSVs the engine and Java tests read from data/.
#    The real IMDB set is fetched from a GitHub Release in CI; for local/agent
#    development we use the committed synthetic fixtures, which share the same
#    schema and file names.
for f in title workedon name; do
  cp -f "data/fixtures/synthetic/${f}.csv" "data/${f}.csv"
done

# 3. Prime the Java build: download dependencies and compile main + test sources.
mvn -B -q -DskipTests test-compile

# 4. Install web showcase dependencies from the lockfile.
(cd web && npm ci)

echo "ReDB install complete."
