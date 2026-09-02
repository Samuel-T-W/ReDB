import static org.junit.jupiter.api.Assertions.*;

import buffer.BufferManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Admission-control tests for QueryEngine on a deliberately tight shared pool.
 *
 * The pools here are sized so the pool physically cannot hold more than
 * maxConcurrentQueries queries at their peak pin counts. Every submitted query
 * completing with baseline-correct rows therefore proves the semaphore gates
 * admission (blocks excess queries instead of rejecting them) and that the
 * per-query frame budget keeps concurrent queries within the pool.
 *
 * Lives in the default package because RunQuery and PreProcessor are in the
 * default package, which named packages cannot reference.
 */
public class QueryEngineTest {

	// Budget 9 gives BNL N = 4; a query's peak simultaneous pins are 2N block
	// pages plus one transient scan/index pin, i.e. exactly 9.
	private static final int QUERY_FRAME_BUDGET = 9;

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
        SyntheticQueryFixtures.load();
	}

	@AfterAll
	static void deleteTables() {
		for (String file : new String[]{"movies.db", "workedon.db", "people.db", "title.idx"}) {
			new File(file).delete();
		}
	}

	@Test
	public void constructorRejectsBudgetBelowBnlMinimum() {
		// 8 / 3 = 2 frames per query, below the BNL minimum of 3
		assertThrows(IllegalArgumentException.class, () -> new QueryEngine(8, 3));
		assertThrows(IllegalArgumentException.class, () -> new QueryEngine(30, 0));
	}

	@Test
	public void singleQueryCompletesAtMinimumFrameBudget() throws Exception {
		QueryEngine engine = new QueryEngine(RunQuery.MIN_FRAME_BUDGET, 1);
		assertEquals(RunQuery.MIN_FRAME_BUDGET, engine.getFrameBudget());
		assertEquals(1, RunQuery.blockPagesPerJoin(engine.getFrameBudget()));

		Path out = tempDir.resolve("min-budget.csv");
		long rows = engine.runQuery("carmencita-1000", "carmencita-1499", false, out);
		assertTrue(rows > 0, "minimum-budget join must still produce rows");
		assertEquals(0, engine.getBufferManager().getTotalPinCount(),
				"all pages must be unpinned after the query closes");
	}

	@Test
	public void tightPoolReservesOneWorkingFramePerAdmittedQuery() {
		int bufferSize = 36;
		int maxConcurrent = 4;
		QueryEngine engine = new QueryEngine(bufferSize, maxConcurrent);

		int blockPages = RunQuery.blockPagesPerJoin(engine.getFrameBudget());

		assertTrue(
				bufferSize - (maxConcurrent * 2 * blockPages) >= maxConcurrent,
				"both BNL blocks must leave at least one working frame per admitted query");
	}

	@Test
	public void queriesBeyondSinglePermitQueueAndComplete() throws Exception {
		// A 9-frame pool fits exactly ONE query at peak. Four queries submitted
		// at once can only all succeed if admission serializes them: a second
		// in-flight query would need 9 more pinned frames and fail eviction.
		QueryEngine engine = new QueryEngine(9, 1);
		assertEquals(9, engine.getFrameBudget());
		runConcurrentlyAndAssertBaselines(engine, 4);
	}

	@RepeatedTest(3)
	public void tightPoolAdmitsQueriesUpToPermitCount() throws Exception {
		// 27 frames / 3 permits = budget 9: the three admitted queries' peak
		// pins exactly cover the pool. Eight submissions exceed the permits, so
		// five of them must queue; all eight must still complete correctly.
		QueryEngine engine = new QueryEngine(27, 3);
		assertEquals(9, engine.getFrameBudget());
		runConcurrentlyAndAssertBaselines(engine, RANGES.length);
	}

	/**
	 * Submits the first {@code queryCount} ranges concurrently (alternating
	 * scan/index access) and asserts each result matches its sequential
	 * baseline and that the shared pool is quiescent afterwards.
	 */
	private void runConcurrentlyAndAssertBaselines(QueryEngine engine, int queryCount)
			throws Exception {
		List<List<String>> baselines = new ArrayList<>();
		for (int i = 0; i < queryCount; i++) {
			baselines.add(computeBaseline(RANGES[i][0], RANGES[i][1], useIndexFor(i)));
		}

		ExecutorService pool = Executors.newFixedThreadPool(queryCount);
		try {
			List<Future<List<String>>> futures = new ArrayList<>();
			for (int i = 0; i < queryCount; i++) {
				final int q = i;
				futures.add(pool.submit(() -> {
					Path out = tempDir.resolve(
							"engine-" + q + "-" + System.nanoTime() + ".csv");
					engine.runQuery(RANGES[q][0], RANGES[q][1], useIndexFor(q), out);
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
		assertEquals(0, engine.getBufferManager().getTotalPinCount(),
				"all pages must be unpinned after queries close");
	}

	// Mixed access methods so scan and index queries share the pool at once
	private static boolean useIndexFor(int queryIndex) {
		return queryIndex % 2 == 1;
	}

	/** Runs one range serially on a fresh private manager. */
	private static List<String> computeBaseline(String start, String end, boolean useIndex)
			throws IOException {
		Path out = tempDir.resolve("baseline-" + System.nanoTime() + ".csv");
		List<String> rows = SequentialBaselines.compute(
				new SequentialBaselines.Spec(start, end, useIndex),
				50,
				QUERY_FRAME_BUDGET,
				out);
		// Guard against a vacuous pass: every range must select some rows
		assertFalse(rows.isEmpty(), "baseline for [" + start + ", " + end + "] is empty");
		return rows;
	}
}
