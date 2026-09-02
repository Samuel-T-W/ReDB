import static org.junit.jupiter.api.Assertions.*;

import buffer.BufferManager;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
import storage.GenericPage;
import storage.GenericRecord;
import storage.Page;
import storage.RawPage;
import util.RecordUtils;

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

	@TempDir
	static Path tempDir;

	@BeforeAll
	static void loadTables() throws IOException {
		// Same setup as the pre_process CLI command: load the three heap files
		// and build the title index (per-record inserts, not bulk load).
        SyntheticQueryFixtures.load();
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
	public void concurrentRangesOwnExclusiveMoviePages() throws IOException {
		int titleLength = RunQuery.MOVIES_SCHEMA.get("title");
		List<Set<Integer>> pagesByRange = new ArrayList<>();
		List<byte[]> starts = new ArrayList<>();
		List<byte[]> ends = new ArrayList<>();
		for (ConcurrentQueryRanges.TitleRange range : ConcurrentQueryRanges.all()) {
			pagesByRange.add(new HashSet<>());
			starts.add(RecordUtils.toFixedBytes(range.start(), titleLength));
			ends.add(RecordUtils.toFixedBytes(range.end(), titleLength));
		}

		BufferManager bm = new BufferManager(16);
		int pageCount = RawPage.pageCount(RunQuery.MOVIES_DB, new File(RunQuery.MOVIES_DB).length());
		for (int pageId = 0; pageId < pageCount; pageId++) {
			Page page = bm.getPage(RunQuery.MOVIES_DB, pageId);
			try {
				GenericPage moviePage = new GenericPage(page, RunQuery.MOVIES_SCHEMA);
				int recordCount = ByteBuffer.wrap(moviePage.getByteArray(), 0, Integer.BYTES).getInt();
				for (int slotId = 0; slotId < recordCount; slotId++) {
					GenericRecord movie = (GenericRecord) moviePage.getRecord(slotId);
					byte[] title = movie.getFieldBytes("title");
					for (int rangeIndex = 0;
							rangeIndex < ConcurrentQueryRanges.size();
							rangeIndex++) {
						if (Arrays.compare(title, starts.get(rangeIndex)) >= 0
								&& Arrays.compare(title, ends.get(rangeIndex)) <= 0) {
							pagesByRange.get(rangeIndex).add(pageId);
						}
					}
				}
			} finally {
				bm.unpinPage(RunQuery.MOVIES_DB, pageId);
			}
		}

		for (int rangeIndex = 0; rangeIndex < pagesByRange.size(); rangeIndex++) {
			Set<Integer> pages = pagesByRange.get(rangeIndex);
			assertFalse(pages.isEmpty(), "range " + rangeIndex + " must cover a movie page");

			for (int otherIndex = 0; otherIndex < pagesByRange.size(); otherIndex++) {
				if (otherIndex != rangeIndex) {
					assertTrue(
							java.util.Collections.disjoint(pages, pagesByRange.get(otherIndex)),
							"ranges " + rangeIndex + " and " + otherIndex
									+ " must not share movie pages");
				}
			}
		}
	}

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
		List<List<String>> baselines = new ArrayList<>();
		for (int i = 0; i < ConcurrentQueryRanges.size(); i++) {
			baselines.add(computeBaseline(i, useIndex));
		}

		BufferManager shared = new BufferManager(SHARED_POOL_SIZE);
		RunQuery.registerCatalog(shared);
		// Re-registering must be harmless (idempotent catalog registration)
		RunQuery.registerCatalog(shared);

		for (int i = 0; i < ConcurrentQueryRanges.size(); i++) {
			ConcurrentQueryRanges.TitleRange range = ConcurrentQueryRanges.get(i);
			Path out = tempDir.resolve("seq-" + useIndex + "-" + i + ".csv");
			RunQuery.run(
					range.start(), range.end(), QUERY_FRAME_BUDGET, useIndex, shared, out);
			assertEquals(
					baselines.get(i),
					SequentialBaselines.sortedRows(out),
					"rows for range " + i);
		}
		assertEquals(0, shared.getTotalPinCount(), "all pages must be unpinned after queries close");
	}

	private void assertConcurrentSharedMatchesBaseline(boolean useIndex) throws Exception {
		List<List<String>> baselines = new ArrayList<>();
		for (int i = 0; i < ConcurrentQueryRanges.size(); i++) {
			baselines.add(computeBaseline(i, useIndex));
		}

		BufferManager shared = new BufferManager(SHARED_POOL_SIZE);
		RunQuery.registerCatalog(shared);

		ExecutorService pool = Executors.newFixedThreadPool(ConcurrentQueryRanges.size());
		try {
			List<Future<List<String>>> futures = new ArrayList<>();
			for (int i = 0; i < ConcurrentQueryRanges.size(); i++) {
				final int q = i;
				futures.add(pool.submit(() -> {
					ConcurrentQueryRanges.TitleRange range = ConcurrentQueryRanges.get(q);
					Path out = tempDir.resolve(
							"concurrent-" + useIndex + "-" + q + "-" + System.nanoTime() + ".csv");
					RunQuery.run(
							range.start(), range.end(), QUERY_FRAME_BUDGET, useIndex, shared, out);
					return SequentialBaselines.sortedRows(out);
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
		for (int i = 0; i < ConcurrentQueryRanges.size(); i++) {
			baselines.add(computeBaseline(i, useIndexFor(i)));
		}

		ExecutorService pool = Executors.newFixedThreadPool(ConcurrentQueryRanges.size());
		try {
			List<Future<List<String>>> futures = new ArrayList<>();
			for (int i = 0; i < ConcurrentQueryRanges.size(); i++) {
				final int q = i;
				futures.add(pool.submit(() -> {
					ConcurrentQueryRanges.TitleRange range = ConcurrentQueryRanges.get(q);
					Path out = tempDir.resolve(
							"engine-e2e-" + q + "-" + System.nanoTime() + ".csv");
					engine.runQuery(range.start(), range.end(), useIndexFor(q), out);
					return SequentialBaselines.sortedRows(out);
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
				ConcurrentQueryRanges.size(), serialMillis, concurrentMillis);
	}

	/** Runs all ranges through the engine, one at a time or all at once. */
	private void runAllThroughEngine(QueryEngine engine, boolean concurrent) throws Exception {
		if (!concurrent) {
			for (int i = 0; i < ConcurrentQueryRanges.size(); i++) {
				ConcurrentQueryRanges.TitleRange range = ConcurrentQueryRanges.get(i);
				Path out = tempDir.resolve("engine-serial-" + i + "-" + System.nanoTime() + ".csv");
				engine.runQuery(range.start(), range.end(), useIndexFor(i), out);
			}
			return;
		}
		ExecutorService pool = Executors.newFixedThreadPool(ConcurrentQueryRanges.size());
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < ConcurrentQueryRanges.size(); i++) {
				final int q = i;
				futures.add(pool.submit(() -> {
					ConcurrentQueryRanges.TitleRange range = ConcurrentQueryRanges.get(q);
					Path out = tempDir.resolve(
							"engine-timed-" + q + "-" + System.nanoTime() + ".csv");
					engine.runQuery(range.start(), range.end(), useIndexFor(q), out);
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
		ConcurrentQueryRanges.TitleRange range = ConcurrentQueryRanges.get(rangeIndex);
		Path out = tempDir.resolve("baseline-mixed-" + rangeIndex + "-" + System.nanoTime() + ".csv");
		List<String> rows = SequentialBaselines.compute(
				new SequentialBaselines.Spec(
						range.start(), range.end(), useIndex),
				SHARED_POOL_SIZE,
				QUERY_FRAME_BUDGET,
				out);
		// Guard against a vacuous pass: every range must select some rows
		assertFalse(rows.isEmpty(), "baseline for range " + rangeIndex + " is empty");
		return rows;
	}

}
