import static org.junit.jupiter.api.Assertions.*;

import buffer.BufferManager;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies a FAILED query leaves no footprint on a shared BufferManager:
 * failed plan construction (after the per-query temp table is registered) and
 * failed operator open (after BNL block pages are pinned) must both release
 * every pin, drop every per-query catalog entry, surface the original
 * exception rather than a cleanup exception, and leave the manager usable for
 * subsequent queries.
 *
 * Lives in the default package because RunQuery and PreProcessor are in the
 * default package, which named packages cannot reference.
 */
public class RunQueryFailureTest {

	private static final Set<String> BASE_FILES =
			Set.of("movies.db", "workedon.db", "people.db", "title.idx");

	@TempDir
	static Path tempDir;

	@BeforeAll
	static void loadTables() throws IOException {
		// Stale temp files from a previous crashed/failed run would trip the
		// no-residue assertions below, so start from a clean slate.
		deleteQueryTempFiles();
		PreProcessor.run();
	}

	private static void deleteQueryTempFiles() {
		String[] stale = new File(".").list((dir, name) -> name.startsWith(".redb-query-"));
		if (stale == null) {
			return;
		}
		for (String name : stale) {
			new File(name).delete();
		}
	}

	@AfterAll
	static void deleteTables() {
		for (String file : BASE_FILES) {
			new File(file).delete();
		}
	}

	@Test
	public void failedScanConstructionLeavesNoTempState() throws IOException {
		BufferManager shared = new BufferManager(40);
		RunQuery.registerCatalog(shared);

		// A base file whose size is not a page multiple makes the Scan
		// constructor throw, after the per-query temp table is registered but
		// before the execution try/finally is reached.
		IllegalStateException failure = corruptAndRun("workedon.db", shared, false);
		assertTrue(failure.getMessage().contains("File size is not a multiple of pages"),
				"the original construction failure must propagate, got: " + failure.getMessage());

		assertNoQueryResidue(shared);
		assertManagerStillUsable(shared);
	}

	@Test
	public void failedIndexOpenLeavesNoTempState() throws IOException {
		BufferManager shared = new BufferManager(40);
		RunQuery.registerCatalog(shared);

		// A corrupt index file makes BTreeManager.openExisting throw partway
		// through plan construction (after the base Scans are built).
		IllegalStateException failure = corruptAndRun("title.idx", shared, true);
		assertTrue(failure.getMessage().contains("Index file size is not a multiple of pages"),
				"the original openExisting failure must propagate, got: " + failure.getMessage());

		assertNoQueryResidue(shared);
		assertManagerStillUsable(shared);
	}

	@Test
	public void partialOpenReleasesPinsAndKeepsOriginalException() throws IOException {
		// Pool of 6 frames with a frame budget of 9 (block size N = 4): the
		// second join's block load must run out of frames while the first
		// join already holds its block pages pinned, failing open() partway.
		BufferManager shared = new BufferManager(6);
		RunQuery.registerCatalog(shared);

		Path out = tempDir.resolve("partial-open.csv");
		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> RunQuery.run("carmencita-1000", "carmencita-1499", 9, false, shared, out));

		// The pool-exhaustion failure must surface as-is; a "Cannot discard
		// file with pinned page" here would mean cleanup ran against pages the
		// half-opened operator tree failed to release, masking the real error.
		assertTrue(failure.getMessage().contains("All frames are pinned"),
				"the original pool-exhaustion failure must propagate, got: " + failure.getMessage());
		assertEquals(0, failure.getSuppressed().length,
				"cleanup must succeed once the failed open has released its pins");

		assertNoQueryResidue(shared);
		assertManagerStillUsable(shared);
	}

	/**
	 * Extends the given base file by a few bytes so its size is no longer a
	 * page multiple, runs the query on the shared manager expecting an
	 * IllegalStateException, and restores the file before returning.
	 */
	private IllegalStateException corruptAndRun(
			String baseFile, BufferManager shared, boolean useIndex) throws IOException {
		File file = new File(baseFile);
		long originalLength = file.length();
		try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
			raf.setLength(originalLength + 7);
			Path out = tempDir.resolve("corrupt-" + baseFile + ".csv");
			return assertThrows(IllegalStateException.class,
					() -> RunQuery.run("carmencita-1000", "carmencita-1499", 9, useIndex, shared, out));
		} finally {
			try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
				raf.setLength(originalLength);
			}
		}
	}

	private void assertNoQueryResidue(BufferManager shared) {
		assertTrue(BASE_FILES.containsAll(shared.bufferedFileIds()),
				"only base-table/index pages may remain in the pool, found: "
						+ shared.bufferedFileIds());
		assertEquals(BASE_FILES, shared.catalogFileNames(),
				"catalog must hold exactly the base entries after a failed query");
		assertEquals(0, shared.getTotalPinCount(),
				"a failed query must not leave pinned pages behind");

		String[] leftovers = new File(".").list((dir, name) -> name.startsWith(".redb-query-"));
		assertNotNull(leftovers);
		assertEquals(0, leftovers.length,
				"no per-query temp files may remain on disk: " + String.join(", ", leftovers));
	}

	/** A healthy query on the same manager must still work after the failure. */
	private void assertManagerStillUsable(BufferManager shared) throws IOException {
		Path out = tempDir.resolve("recovery-" + System.nanoTime() + ".csv");
		long rows = RunQuery.run("carmencita-1000", "carmencita-1499", 3, false, shared, out);
		assertTrue(rows > 0, "the shared manager must still serve queries after a failed one");
	}
}
