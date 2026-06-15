#!/usr/bin/env bash
# correctness_test.sh — run_query vs PostgreSQL end-to-end correctness check
#
# Usage:  ./scripts/correctness_test.sh [start_range] [end_range] [buffer_size]
# Example: ./scripts/correctness_test.sh "carmencita" "carmencita" 20
#
# Steps:
#   1. Compile (if target/classes is missing)
#   2. Run pre_process (if DB files are missing)
#   3. Run run_query, sort query_results.csv -> /tmp/java_output.txt
#   4. Print PostgreSQL setup + diff instructions
set -e
cd "$(dirname "$0")/.."

START="${1:-a}"
END="${2:-az}"
BUFFER="${3:-20}"

JAVA="/usr/local/Cellar/openjdk/21/libexec/openjdk.jdk/Contents/Home/bin/java"
if ! command -v "$JAVA" &>/dev/null; then
    JAVA="java"   # fallback to PATH java
fi

# Step 1: compile if needed
if [ ! -d target/classes ]; then
    echo "Compiling..."
    mvn compile -q
fi

# Step 2: pre_process if DB files are missing
if [ ! -f movies.db ] || [ ! -f workedon.db ] || [ ! -f people.db ]; then
    echo "Running pre_process (this may take a minute)..."
    "$JAVA" -cp target/classes Main pre_process
fi

# Step 3: run query and sort
echo "Running: run_query \"$START\" \"$END\" $BUFFER"
"$JAVA" -cp target/classes Main run_query "$START" "$END" $BUFFER
sort query_results.csv > /tmp/java_output.txt
echo "Java output: $(wc -l < /tmp/java_output.txt | tr -d ' ') line(s) -> /tmp/java_output.txt"
head -5 /tmp/java_output.txt

# Step 4: PostgreSQL instructions
DATA_DIR="$(pwd)/data"
cat <<INSTRUCTIONS

=== PostgreSQL Comparison ===

1. Create tables and load data (run once):

   psql -d mydb <<'SQL'
   CREATE TABLE IF NOT EXISTS movies   (movieId VARCHAR(9),  title    VARCHAR(30));
   CREATE TABLE IF NOT EXISTS workedon (movieId VARCHAR(9),  personId VARCHAR(10), category VARCHAR(20));
   CREATE TABLE IF NOT EXISTS people   (personId VARCHAR(10), name    VARCHAR(105));
   COPY movies   FROM '${DATA_DIR}/title.csv'    CSV HEADER;
   COPY workedon FROM '${DATA_DIR}/workedon.csv' CSV HEADER;
   COPY people   FROM '${DATA_DIR}/name.csv'     CSV HEADER;
   SQL

2. Run the equivalent query (replace values as needed):

   psql -d mydb -t -A -F',' -c "
     SELECT TRIM(m.title), TRIM(p.name)
     FROM movies m
     JOIN workedon w ON TRIM(m.movieId) = TRIM(w.movieId)
     JOIN people   p ON TRIM(w.personId) = TRIM(p.personId)
     WHERE TRIM(m.title) >= '$START'
       AND TRIM(m.title) <= '$END'
       AND TRIM(w.category) = 'director'
   " | sort > /tmp/pg_output.txt

3. Compare outputs:

   diff /tmp/java_output.txt /tmp/pg_output.txt && echo "MATCH" || echo "MISMATCH"

INSTRUCTIONS
