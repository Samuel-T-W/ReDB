import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import buffer.BufferManager;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-query deadlines on QueryEngine: a query cut off mid-execution or while
 * queued fails with QueryTimeoutException and leaves the shared manager, the
 * disk, and the admission semaphore as if it had never run.
 *
 * Lives in the default package because QueryEngine and RunQuery are in the
 * default package, which named packages cannot reference.
 */
public class QueryTimeoutTest {

	private static final Set<String> BASE_FILES =
			Set.of("movies.db", "workedon.db", "people.db", "title.idx");

	// A 3-frame pool (one query, BNL N = 1) evicts on nearly every pin, so the
	// full-range scan query spends its time in channel I/O and pins thousands
	// of pages: plenty of points for a deadline interrupt to land on.
	private static final int POOL = 3;

	@TempDir
	static Path tempDir;

	@BeforeAll
	static void loadTables() throws IOException {
		SyntheticQueryFixtures.load();
	}

	@AfterAll
	static void deleteTables() {
		for (String file : BASE_FILES) {
			new File(file).delete();
		}
	}

	@Test
	public void runningQueryPastDeadlineIsCancelledAndCleanedUp() throws Exception {
		long baselineNanos = timeScanQuery(new QueryEngine(POOL, 1));
		assumeTrue(baselineNanos >= Duration.ofMillis(5).toNanos(),
				"query too fast to cut off reliably: " + baselineNanos + " ns");

		// Deadlines at several fractions of the full run interrupt the query at
		// different stages (materialization, join 1, join 2).
		for (double fraction : new double[] {0.1, 0.3, 0.6}) {
			Duration timeout = Duration.ofNanos(Math.max(
					Duration.ofMillis(1).toNanos(), (long) (baselineNanos * fraction)));
			QueryEngine engine = new QueryEngine(POOL, 1, timeout);

			QueryTimeoutException e = assertThrows(QueryTimeoutException.class,
					() -> runScanQuery(engine), "deadline " + timeout);
			assertTrue(e.admitted(), "the query was running when the deadline hit");
			assertFalse(Thread.currentThread().isInterrupted(),
					"the deadline interrupt must not leak into the caller");
			assertEngineQuiescent(engine);

			// The admission slot came back: a cheap query on the same
			// single-permit engine is admitted and completes in time.
			Path out = tempDir.resolve("after-timeout-" + System.nanoTime() + ".csv");
			long rows = engine.runQuery("carmencita-0100", "carmencita-0109", true, out);
			assertTrue(rows > 0, "follow-up query must produce rows");
			assertEngineQuiescent(engine);
		}
	}

	@Test
	public void queuedQueryPastDeadlineFailsWithoutRunning() throws Exception {
		QueryEngine engine = new QueryEngine(POOL, 1, Duration.ofMillis(100));
		// Hold the only permit, as a long-running query would.
		engine.getAdmission().acquire();
		Path out = tempDir.resolve("queued-" + System.nanoTime() + ".csv");
		try {
			QueryTimeoutException e = assertThrows(QueryTimeoutException.class,
					() -> engine.runQuery("carmencita-0100", "carmencita-0109", true, out));
			assertFalse(e.admitted(), "the query must time out in the admission queue");
		} finally {
			engine.getAdmission().release();
		}
		assertFalse(Files.exists(out), "a query that never ran must not write output");
		assertEquals(1, engine.getAdmission().availablePermits(),
				"a query that never got a permit must not release one");
		assertEngineQuiescent(engine);
	}

	@Test
	public void constructorRejectsNonPositiveTimeout() {
		assertThrows(IllegalArgumentException.class,
				() -> new QueryEngine(POOL, 1, Duration.ZERO));
		assertThrows(IllegalArgumentException.class,
				() -> new QueryEngine(POOL, 1, Duration.ofMillis(-1)));
	}

	private static long timeScanQuery(QueryEngine engine) throws Exception {
		runScanQuery(engine); // warm the OS cache for the timed run
		long start = System.nanoTime();
		runScanQuery(engine);
		return System.nanoTime() - start;
	}

	private static long runScanQuery(QueryEngine engine) throws Exception {
		Path out = tempDir.resolve("scan-" + System.nanoTime() + ".csv");
		return engine.runQuery("carmencita-1000", "carmencita-1199", false, out);
	}

	private static void assertEngineQuiescent(QueryEngine engine) throws Exception {
		BufferManager bm = engine.getBufferManager();
		assertEquals(0, bm.getTotalPinCount(), "no pins may survive the query");
		assertEquals(List.of(), checkInvariants(bm));
		assertTrue(BASE_FILES.containsAll(bm.bufferedFileIds()),
				"only base-table/index pages may remain, found: " + bm.bufferedFileIds());
		assertEquals(BASE_FILES, bm.catalogFileNames(),
				"per-query catalog entries must be unregistered");
		assertEquals(1, engine.getAdmission().availablePermits(),
				"the admission slot must be released");
		String[] leftovers = new File(".").list((dir, name) -> name.startsWith(".redb-query-"));
		assertNotNull(leftovers);
		assertEquals(0, leftovers.length,
				"no per-query temp files may remain on disk: " + String.join(", ", leftovers));
	}

	// checkInvariants is package-private to buffer; this test cannot live there
	// because QueryEngine is in the default package.
	@SuppressWarnings("unchecked")
	private static List<String> checkInvariants(BufferManager bm) throws Exception {
		Method m = BufferManager.class.getDeclaredMethod("checkInvariants");
		m.setAccessible(true);
		return (List<String>) m.invoke(bm);
	}
}
