# ReDB

**[Live demo →](https://samuel-t-w.github.io/ReDB/)**

A relational database storage and query engine built from scratch in Java. ReDB implements the core machinery that sits underneath a real database — paged disk storage, a buffer pool with its own eviction policy, a B+ tree index, and a pull-based query executor with block nested loop joins — without leaning on any existing database libraries.

It's a study in how databases actually work below the SQL layer: every byte on disk, every page in memory, and every record that flows through a query is managed by code in this repo.

## What's implemented

### Storage engine

- Fixed 4096-byte pages persisted to disk via `RandomAccessFile`, addressed by offset (`pageId * 4096`).
- Slotted page layout for fixed-length records, with a schema-driven record builder.
- A clean separation between raw page bytes (`Page` / `RawPage`) and record-level logic (`DataPage` / `GenericPage`).

### Buffer manager

- Fixed-size buffer pool with **LRU eviction**, implemented over a `LinkedHashMap` access order.
- Pin counts and dirty-flag tracking; only unpinned pages are eligible for eviction.
- A multi-file catalog so multiple tables and index files share one buffer pool, keyed by `(fileId, pageId)`.

### B+ tree index

- Full B+ tree with internal and leaf nodes, supporting insert with cascading node splits (up to creating a new root).
- Exact-match search, inclusive range search via a doubly-linked leaf chain, and an O(n) bulk-load path for pre-sorted input.
- Byte-level key comparison directly on raw bytes — no deserialization on the hot path.
- Parent pointers stored in every node header to avoid re-traversing from the root during splits.

### Query executor

- Pull-based iterator model: every operator implements `open()` / `next()` / `close()`, and a parent drives its children one record at a time.
- Operators: sequential scan, index scan (via the B+ tree), selection, projection, and **block nested loop join**.
- BNL join keeps an in-memory hash table per block and sizes its blocks against the available buffer frames, materializing intermediate results to a temp file through the buffer manager.

## Architecture

```text
src/
  buffer/     Buffer pool (LRU), frames, page keys, multi-file catalog
  storage/    Pages, records, and the B+ tree (nodes, splits, search, bulk load)
  operators/  Pull-based query operators (scan, index scan, select, project, join)
  util/       Record serialization and pre-processing helpers
test/         Unit, end-to-end, and performance tests
```

### Design highlights

- **Page size** is a fixed 4096 bytes throughout.
- **LRU** is realized by removing and re-inserting a `LinkedHashMap` entry on each access; eviction scans from the front for the first unpinned page.
- **Node headers** carry `parentId` so splits never re-walk the tree from the root.
- **Splits** distinguish copy-up (leaf — the separator stays in both siblings) from push-up (internal — the separator moves up and exists only in the parent).
- **Keys** are fixed-length and compared with `Arrays.compare()` on raw bytes.

## Prerequisites

- Java 21 or higher
- Apache Maven 3.6 or higher
- A CSV data set placed in `data/` (see *Data* below)

## Building

```bash
mvn compile
```

## Running tests

```bash
mvn test                                # full suite
mvn test -Dtest=PageTest                # storage / record unit tests
mvn test -Dtest=BufferManagerTest       # buffer pool unit tests
mvn test -Dtest=BTreeUnitTest           # B+ tree unit tests
mvn test -Dtest=End2EndTest             # integration test (loads CSV data)
```

## Using the engine

### Pre-process (load tables, optionally build the index)

```bash
./run.sh pre_process
```

Loads the source CSVs into binary heap files and, optionally, builds a B+ tree index over the title column.

### Run a range query

```bash
./run.sh run_query <start_range> <end_range> <buffer_size>
```

Example:

```bash
./run.sh run_query "the" "thez" 20
```

The query finds the people associated with records whose title falls in `[start_range, end_range]`, executing a fixed plan of selections, projections, and block nested loop joins. Results are written to `query_results.csv`.

## Data

ReDB operates on fixed-length records loaded from CSV. The engine was developed and tested against the public [IMDB datasets](https://developer.imdb.com/non-commercial-datasets/) with three relations:

| Relation   | Schema                                                        |
| ---------- | ------------------------------------------------------------- |
| `Movies`   | `movieId: char(9)`, `title: char(30)`                         |
| `WorkedOn` | `movieId: char(9)`, `personId: char(10)`, `category: char(20)`|
| `People`   | `personId: char(10)`, `name: char(105)`                       |

Data files are not committed to this repository; place your own CSVs in `data/` before running `pre_process`.

## Limitations

ReDB is an educational engine, not a production database. By design it is single-threaded, supports fixed-length records only, has no transactions or recovery, no deletions in the B+ tree, and one file per table. Page and record IDs are 32-bit ints, capping files at ~2 GB.

## License

See [LICENSE](LICENSE) if present; otherwise all rights reserved by the authors.
