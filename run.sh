#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if [ ! -d target/classes ]; then
  mvn compile -q
fi

java -cp target/classes Main "$@"
