# Archived benchmark runs

These archives preserve the ReDB benchmark evidence needed by local and cloud agents.

`small-imdb-20260811.tar.gz` contains the aligned legacy multi-JVM and shared-engine runs against the original smaller IMDb dataset.
Both runs used the 12-query concurrency workload and returned 11,047, 14,525, or 24,225 rows per query range.

`full-imdb-20260816.tar.gz` contains the legacy and budgeted shared-engine outputs from the full IMDb run, plus the exact `redb_bench.sh` driver and shared-run log recovered from temporary session storage.
The full run used the 2026-08-15 snapshot with 12,717,779 movie rows, 101,214,175 credit rows, and 15,576,470 person rows.
The archived full run has a known setup asymmetry: the shared run used a 4 GiB cgroup, disabled swap, dropped caches, and ran three repetitions, while the legacy run used its direct Python harness with five repetitions.

Extract either archive from the repository root with `tar -xzf benchmark/results/archive/<archive>.tar.gz`.

SHA-256 checksums:

```text
b03e8603255bcf226e96cff1d0bbe1f9253fa45c9824daa0934f0df8c0ddb800  small-imdb-20260811.tar.gz
258da4dcc77c7b7e1e2abe5dd3459eb3f8f519e2abaf32a5f48f895de5e9e082  full-imdb-20260816.tar.gz
```
