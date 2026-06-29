# ReDB Showcase

An interactive web page for the ReDB storage engine.
Each engine iteration gets a page; the implemented ones embed a live, step-through demo of a query running through the buffer pool, B+ tree index, and block nested-loop joins.

The default demo replays the committed engine artifact at `public/data/query-trace-default.json`.
That file is captured by the Java engine from a real `run_query` execution and conforms to the Java trace schema in `src/trace/*.java`.

`src/data/generateTrace.ts` remains the synthetic fallback and comparison path.
The loader uses the saved artifact only when its run metadata matches the active controls; missing, invalid, or non-matching artifacts fall back to generated traces so the demo controls keep working.

## Stack

- React 18 + React Router (hash routing, so the static build works from any subpath)
- TypeScript, built with Vite
- Vitest + Testing Library for tests

## Develop

```bash
npm install      # once
npm run dev      # dev server on http://localhost:5180
npm run build    # type-check + production build into dist/
```

## Trace data

Refresh the committed real trace from the repo root:

```bash
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" DemoTraceExport
```

The exporter creates a deterministic miniature database, runs the same Java query path as `run_query`, and writes `web/public/data/query-trace-default.json`.
Keep `src/data/generateTrace.ts` and its tests in place; they are the fallback for unavailable artifacts and for settings that do not match the committed trace.

## Test

```bash
npm test         # run the suite once
npm run test:watch
```

Tests live next to the code they cover (`*.test.ts` / `*.test.tsx`):

- `src/data/` — the trace generator, the replay reducer, and the static iteration/preset data. These are pure functions and carry the bulk of the logic, so they get the bulk of the coverage.
- `src/components/` — render/interaction smoke tests for routing and the demo player.

CI runs `npm test` and `npm run build` on every push and PR (see `.github/workflows/ci.yml`).

## Layout

```
src/
  App.tsx                 # routes: /iteration/:id and /planned
  components/
    IterationPage.tsx     # one engine iteration: header, demo, side panels
    PlannedWork.tsx       # roadmap list of all iterations
    demo/                 # the live query-replay player and its panels
  data/
    iterations.ts         # one entry per engine iteration
    presets.ts            # title-range and buffer-size presets the demo offers
    generateTrace.ts      # builds a schema-valid QueryTrace from demo settings
    loadTrace.ts          # loads/validates the saved artifact, then falls back
    replay.ts             # replays a trace up to a cursor into UI state
  types/trace.ts          # TypeScript mirror of the Java trace model
```
