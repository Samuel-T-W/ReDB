-- query.sql — PostgreSQL equivalent of run_query for correctness validation
--
-- Load tables once:
--   psql -d mydb -f scripts/query.sql
--
-- Then run the parameterized query:
--   psql -d mydb -v start_range="'carmencita'" -v end_range="'carmencita'" -f scripts/query.sql

-- ── Table creation ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS movies (
    movieId  VARCHAR(9),
    title    VARCHAR(30)
);

CREATE TABLE IF NOT EXISTS workedon (
    movieId  VARCHAR(9),
    personId VARCHAR(10),
    category VARCHAR(20)
);

CREATE TABLE IF NOT EXISTS people (
    personId VARCHAR(10),
    name     VARCHAR(105)
);

-- ── Load data (adjust paths as needed) ──────────────────────────────────────
-- COPY movies   FROM '/absolute/path/to/data/title.csv'    CSV HEADER;
-- COPY workedon FROM '/absolute/path/to/data/workedon.csv' CSV HEADER;
-- COPY people   FROM '/absolute/path/to/data/name.csv'     CSV HEADER;

-- ── Equivalent query ─────────────────────────────────────────────────────────
-- Replace :start_range and :end_range with literal strings, e.g. 'carmencita'
SELECT TRIM(m.title), TRIM(p.name)
FROM   movies   m
JOIN   workedon w ON TRIM(m.movieId)  = TRIM(w.movieId)
JOIN   people   p ON TRIM(w.personId) = TRIM(p.personId)
WHERE  TRIM(m.title)    >= :start_range
  AND  TRIM(m.title)    <= :end_range
  AND  TRIM(w.category)  = 'director'
ORDER  BY 1, 2;