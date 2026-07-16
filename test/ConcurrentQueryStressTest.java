import static org.junit.jupiter.api.Assertions.*;

import buffer.BufferManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Multi-query stress test against one shared BufferManager.
 *
 * Every query's result rows are compared to a sequential baseline computed on a
 * fresh private manager, so any cross-query interference through the shared
 * pool (lost pins, wrong eviction, stale pages) shows up as a row diff.
 *
 * Lives in the default package because RunQuery and PreProcessor are in the
 * default package, which named packages cannot reference.
 */
public class ConcurrentQueryStressTest {

	// Each query gets a frame budget of 9 (BNL N = 4 block pages). A query's
	// worst-case simultaneous pins are 2N block pages (both joins hold their
	// blocks at once) plus one transient scan/index pin, i.e. 9. There is no
	// admission control yet, so the pool must cover the worst case of all 8
	// queries at peak (72 pinned frames) or "all frames pinned" is a legitimate
	// outcome. 96 frames leaves that headroom while staying far below the ~200
	// data pages the queries touch, so eviction contention stays constant.
	private static final int SHARED_POOL_SIZE = 96;
	private static final int QUERY_FRAME_BUDGET = 9;

	private static final Set<String> BASE_FILES =
			Set.of("movies.db", "workedon.db", "people.db", "title.idx");

	// Title ranges over the dataset loaded by PreProcessor. Several overlap so
	// concurrent queries hit the same base-table pages.
	private static final String[][] RANGES = {
			{"carmencita", "carmencita-0099"},
			{"carmencita-0100", "carmencita-0299"},
			{"carmencita-0200", "carmencita-0399"},
			{"carmencita-0350", "carmencita-0549"},
			{"carmencita-1000", "carmencita-1499"},
			{"carmencita-1250", "carmencita-1749"},
			{"carmencita-2000", "carmencita-2199"},
			{"carmencita-3000", "carmencita-3999"},
	};

	@TempDir
	static Path tempDir;

	@BeforeAll
	static void loadTables() throws IOException {
		// Same setup as the pre_process CLI command: load the three heap files
		// and build the title index (per-record inserts, not bulk load).
		PreProcessor.run();
	}

	@AfterAll
	static void deleteTables() {
		for (String file : new String[]{"movies.db", "workedon.db", "people.db", "title.idx"}) {
			new File(file).delete();
		}
	}

	// ----------------------
	// Sequential plumbing: shared manager reused across queries, one at a time
	// ----------------------

	@Test
	public void sequentialQueriesOnSharedManagerMatchBaselineScan() throws IOException {
		assertSequentialSharedMatchesBaseline(false);
	}

	@Test
	public void sequentialQueriesOnSharedManagerMatchBaselineIndex() throws IOException {
		assertSequentialSharedMatchesBaseline(true);
	}

	// ----------------------
	// Concurrent runs: one thread per query on the shared manager
	// ----------------------

	@RepeatedTest(3)
	public void concurrentQueriesOnSharedManagerMatchBaselineScan() throws Exception {
		assertConcurrentSharedMatchesBaseline(false);
	}

	@RepeatedTest(3)
	public void concurrentQueriesOnSharedManagerMatchBaselineIndex() throws Exception {
		assertConcurrentSharedMatchesBaseline(true);
	}

	private void assertSequentialSharedMatchesBaseline(boolean useIndex) throws IOException {
		List<List<String>> baselines = computeBaselines(useIndex);

		BufferManager shared = new BufferManager(SHARED_POOL_SIZE);
		RunQuery.registerCatalog(shared);
		// Re-registering must be harmless (idempotent catalog registration)
		RunQuery.registerCatalog(shared);

		for (int i = 0; i < RANGES.length; i++) {
			Path out = tempDir.resolve("seq-" + useIndex + "-" + i + ".csv");
			RunQuery.run(RANGES[i][0], RANGES[i][1], QUERY_FRAME_BUDGET, useIndex, shared, out);
			assertEquals(baselines.get(i), sortedRows(out), "rows for range " + i);
		}
		assertEquals(0, shared.getTotalPinCount(), "all pages must be unpinned after queries close");
	}

	private void assertConcurrentSharedMatchesBaseline(boolean useIndex) throws Exception {
		List<List<String>> baselines = computeBaselines(useIndex);

		BufferManager shared = new BufferManager(SHARED_POOL_SIZE);
		RunQuery.registerCatalog(shared);

		ExecutorService pool = Executors.newFixedThreadPool(RANGES.length);
		try {
			List<Future<List<String>>> futures = new ArrayList<>();
			for (int i = 0; i < RANGES.length; i++) {
				final int q = i;
				futures.add(pool.submit(() -> {
					Path out = tempDir.resolve(
							"concurrent-" + useIndex + "-" + q + "-" + System.nanoTime() + ".csv");
					RunQuery.run(RANGES[q][0], RANGES[q][1], QUERY_FRAME_BUDGET, useIndex, shared, out);
					return sortedRows(out);
				}));
			}
			// get() rethrows worker exceptions in the test thread
			for (int i = 0; i < futures.size(); i++) {
				assertEquals(baselines.get(i), futures.get(i).get(), "rows for range " + i);
			}
		} finally {
			pool.shutdownNow();
		}
		assertEquals(0, shared.getTotalPinCount(), "all pages must be unpinned after queries close");
	}

	// ----------------------
	// End-to-end through QueryEngine, the real user-facing path: admission
	// control queues excess queries, per-query budgets share the pool, and the
	// per-query cleanup keeps the pool and catalog free of dead entries.
	// ----------------------

	@RepeatedTest(3)
	public void concurrentMixedQueriesThroughEngineMatchBaseline() throws Exception {
		// 36 frames / 4 permits = budget 9, so half of the 8 submissions must
		// queue for admission while scan and index queries share the pool.
		QueryEngine engine = new QueryEngine(36, 4);

		List<List<String>> baselines = new ArrayList<>();
		for (int i = 0; i < RANGES.length; i++) {
			baselines.add(computeBaseline(i, useIndexFor(i)));
		}

		ExecutorService pool = Executors.newFixedThreadPool(RANGES.length);
		try {
			List<Future<List<String>>> futures = new ArrayList<>();
			for (int i = 0; i < RANGES.length; i++) {
				final int q = i;
				futures.add(pool.submit(() -> {
					Path out = tempDir.resolve(
							"engine-e2e-" + q + "-" + System.nanoTime() + ".csv");
					engine.runQuery(RANGES[q][0], RANGES[q][1], useIndexFor(q), out);
					return sortedRows(out);
				}));
			}
			// get() rethrows worker exceptions in the test thread
			for (int i = 0; i < futures.size(); i++) {
				assertEquals(baselines.get(i), futures.get(i).get(), "rows for range " + i);
			}
		} finally {
			pool.shutdownNow();
		}

		BufferManager shared = engine.getBufferManager();
		assertEquals(0, shared.getTotalPinCount(), "all pages must be unpinned after queries close");
		assertTrue(BASE_FILES.containsAll(shared.bufferedFileIds()),
				"only base-table/index pages may remain in the pool, found: " + shared.bufferedFileIds());
		assertEquals(BASE_FILES, shared.catalogFileNames(),
				"catalog must hold exactly the base entries after all queries");
	}

	@Test
	public void printSerialVsConcurrentEngineTiming() throws Exception {
		// Throughput sanity check, deliberately NOT asserted: CI machines vary
		// too much for a hard bound. Prints wall time for the 8 mixed queries
		// run one at a time versus all submitted at once, on identically sized
		// fresh engines (a warm-up pass first levels the OS file cache).
		runAllThroughEngine(new QueryEngine(72, 8), false);

		long serialStart = System.nanoTime();
		runAllThroughEngine(new QueryEngine(72, 8), false);
		long serialMillis = (System.nanoTime() - serialStart) / 1_000_000;

		long concurrentStart = System.nanoTime();
		runAllThroughEngine(new QueryEngine(72, 8), true);
		long concurrentMillis = (System.nanoTime() - concurrentStart) / 1_000_000;

		System.out.printf("QueryEngine timing for %d mixed queries: serial %d ms, concurrent %d ms%n",
				RANGES.length, serialMillis, concurrentMillis);
	}

	/** Runs all ranges through the engine, one at a time or all at once. */
	private void runAllThroughEngine(QueryEngine engine, boolean concurrent) throws Exception {
		if (!concurrent) {
			for (int i = 0; i < RANGES.length; i++) {
				Path out = tempDir.resolve("engine-serial-" + i + "-" + System.nanoTime() + ".csv");
				engine.runQuery(RANGES[i][0], RANGES[i][1], useIndexFor(i), out);
			}
			return;
		}
		ExecutorService pool = Executors.newFixedThreadPool(RANGES.length);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < RANGES.length; i++) {
				final int q = i;
				futures.add(pool.submit(() -> {
					Path out = tempDir.resolve(
							"engine-timed-" + q + "-" + System.nanoTime() + ".csv");
					engine.runQuery(RANGES[q][0], RANGES[q][1], useIndexFor(q), out);
					return null;
				}));
			}
			for (Future<?> f : futures) {
				f.get(); // rethrows worker exceptions in the test thread
			}
		} finally {
			pool.shutdownNow();
		}
	}

	// Mixed access methods so scan and index queries share the pool at once
	private static boolean useIndexFor(int queryIndex) {
		return queryIndex % 2 == 1;
	}

	/** Runs one range serially on a fresh private manager. */
	private static List<String> computeBaseline(int rangeIndex, boolean useIndex) throws IOException {
		BufferManager bm = new BufferManager(SHARED_POOL_SIZE);
		RunQuery.registerCatalog(bm);
		Path out = tempDir.resolve("baseline-mixed-" + rangeIndex + "-" + System.nanoTime() + ".csv");
		RunQuery.run(RANGES[rangeIndex][0], RANGES[rangeIndex][1], QUERY_FRAME_BUDGET, useIndex, bm, out);
		List<String> rows = sortedRows(out);
		// Guard against a vacuous pass: every range must select some rows
		assertFalse(rows.isEmpty(), "baseline for range " + rangeIndex + " is empty");
		return rows;
	}

	/** Runs every range serially, each on a fresh private manager. */
	private static List<List<String>> computeBaselines(boolean useIndex) throws IOException {
		List<List<String>> baselines = new ArrayList<>();
		for (int i = 0; i < RANGES.length; i++) {
			BufferManager bm = new BufferManager(SHARED_POOL_SIZE);
			RunQuery.registerCatalog(bm);
			Path out = tempDir.resolve("baseline-" + useIndex + "-" + i + "-" + System.nanoTime() + ".csv");
			RunQuery.run(RANGES[i][0], RANGES[i][1], QUERY_FRAME_BUDGET, useIndex, bm, out);
			List<String> rows = sortedRows(out);
			// Guard against a vacuous pass: every range must select some rows
			assertFalse(rows.isEmpty(), "baseline for range " + i + " is empty");
			baselines.add(rows);
		}
		return baselines;
	}

	private static List<String> sortedRows(Path out) throws IOException {
		List<String> rows = new ArrayList<>(Files.readAllLines(out, StandardCharsets.UTF_8));
		Collections.sort(rows);
		return rows;
	}
}
