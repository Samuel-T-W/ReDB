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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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

	// Deliberately small shared pool so concurrent queries constantly contend
	// on eviction; each query gets a frame budget of 9 (BNL N = 4 block pages).
	private static final int SHARED_POOL_SIZE = 40;
	private static final int QUERY_FRAME_BUDGET = 9;

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

	@Disabled("BufferManager is not yet thread safe; enabled in the thread-safety commits")
	@RepeatedTest(3)
	public void concurrentQueriesOnSharedManagerMatchBaselineScan() throws Exception {
		assertConcurrentSharedMatchesBaseline(false);
	}

	@Disabled("BufferManager is not yet thread safe; enabled in the thread-safety commits")
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
