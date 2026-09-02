# ReDB

**[Web page →](https://samuel-t-w.github.io/ReDB/)**

A read-only relational storage and query engine written from scratch in Java, with no database libraries underneath it.
Every byte on disk, every page in memory, and every record flowing through a query is handled by code in this repo.

It is deliberately narrow: no transactions, no recovery, no write path.
The depth goes into query planning and execution performance instead.

## What's built

- **Paged storage** over `RandomAccessFile`, with fixed-length records in slotted pages.
- **Buffer pool** with LRU eviction, pin counts, dirty tracking, and a multi-file catalog.
- **B+ tree index** supporting insert with cascading splits, exact-match search, range search, and bulk load.
- **Query executor** built on pull-based `open`/`next`/`close` operators: scan, index scan, select, project, and block nested loop join.
- **Concurrent reads** through a thread-safe buffer manager and an admission-controlled `QueryEngine`.
- **Query tracing** that records plan structure, page access, and join behaviour for the web visualizer.

## Layout

```text
src/
  buffer/     Buffer pool, frames, page keys
  catalog/    Table and index entries
  storage/    Pages, records, B+ tree nodes and manager
  operators/  Pull-based query operators
  trace/      Query trace events for the web visualizer
  util/       Serialization and CSV pre-processing
test/         Unit, end-to-end, concurrency, and performance tests
```

## Quickstart

Requires Java 21 and Maven 3.6+.

```bash
mvn compile
mvn test

./run.sh pre_process                        # load CSVs into heap files, build the index
./run.sh run_query "the" "thez" 20          # start, end, buffer size
```

`run_query` finds the people credited on titles in the given range and writes `query_results.csv`.

## Data

ReDB runs on the public [IMDB datasets](https://developer.imdb.com/non-commercial-datasets/), reduced to three fixed-length relations: movies, credits, and people.
The CSVs are not committed; CI pulls them from the `test-fixtures-v1` release, and a local run needs your own copies in `data/`.

## Roadmap

1. **Single-threaded engine** *(done)*
2. **Concurrent reads** *(done)*
3. **General query planner** replacing the fixed plan, choosing access path and join order by cost
4. **Self-describing catalog** holding schemas in the engine
5. **Interactive query box** on the website

## Limitations

An educational engine, not a production database.

- Read-only. The insert and bulk-load code exists to build tables and indexes, not to serve writes.
- Fixed-length records only.
- No transactions or recovery.
- No deletions in the B+ tree.
- One file per table.
