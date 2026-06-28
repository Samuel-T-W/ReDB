# ReDB Showcase

An interactive web page for the ReDB storage engine.
Each engine iteration gets a page; the implemented ones embed a live, step-through demo of a query running through the buffer pool, B+ tree index, and block nested-loop joins.

The demo currently replays a **generated** `QueryTrace` (see `src/data/generateTrace.ts`) that conforms to the Java trace schema in `src/trace/*.java`.
The invented numbers are internally consistent and respond to the controls (title range, buffer size, scan vs. index), so the UI already behaves the way the real engine will once committed engine traces drop in.

## Stack

- React 18 + React Router (hash routing, so the static build works from any subpath)
- TypeScript, built with Vite
- Vitest + Testing Library for tests

## Develop

```bash
npm install      # once
npm run dev      # dev server on http://localhost:5180
npm run build    # type-check + production build into dist/
npm run codegen  # regenerate src/types/trace.ts from the Java trace model
```

`src/types/trace.ts` is **generated**, not hand-written.
The Java records/enums in `../src/trace/*.java` are the source of truth; the [typescript-generator](https://github.com/vojtechhabarta/typescript-generator) Maven plugin (configured in the root `pom.xml`) emits the matching TS unions/interfaces.
`npm run codegen` just runs `mvn process-classes` for you (so it needs the JDK/Maven toolchain).
Run it after changing the Java model.
Field optionality comes from JSpecify `@Nullable` on the Java record components; `Instant` maps to `string`; enum wire names come from `@JsonValue`.
The one thing not generated is `CURRENT_SCHEMA_VERSION` (the plugin emits types only), kept by hand in `src/types/traceSchema.ts`.
CI regenerates and runs `git diff --exit-code` on `trace.ts`, so a forgotten regen fails the build.

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

```text
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
    replay.ts             # replays a trace up to a cursor into UI state
  types/
    trace.ts              # GENERATED from ../src/trace/*.java (do not edit by hand)
    traceSchema.ts        # CURRENT_SCHEMA_VERSION (not emitted by the generator)
```
