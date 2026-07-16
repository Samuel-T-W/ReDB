import static org.junit.jupiter.api.Assertions.*;

import buffer.BufferManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies a query's footprint on a shared BufferManager is fully cleaned up
 * when it finishes: no buffer pool pages for its temp/scratch fileIds, no
 * leftover per-query catalog entries, no pins, and no temp files on disk.
 *
 * Lives in the default package because RunQuery and PreProcessor are in the
 * default package, which named packages cannot reference.
 */
public class QueryLifecycleTest {

	private static final Set<String> BASE_FILES =
			Set.of("movies.db", "workedon.db", "people.db", "title.idx");

	@TempDir
	static Path tempDir;

	@BeforeAll
	static void loadTables() throws IOException {
		PreProcessor.run();
	}

	@AfterAll
	static void deleteTables() {
		for (String file : BASE_FILES) {
			new File(file).delete();
		}
	}

	@Test
	public void queryLeavesNoTempStateOnSharedManagerScan() throws IOException {
		assertQueryLeavesNoTempState(false);
	}

	@Test
	public void queryLeavesNoTempStateOnSharedManagerIndex() throws IOException {
		assertQueryLeavesNoTempState(true);
	}

	private void assertQueryLeavesNoTempState(boolean useIndex) throws IOException {
		BufferManager shared = new BufferManager(40);
		RunQuery.registerCatalog(shared);

		// two queries back to back so leftovers from the first would also show
		// up as accumulation, not just as a single query's residue
		for (int i = 0; i < 2; i++) {
			Path out = tempDir.resolve("lifecycle-" + useIndex + "-" + i + ".csv");
			long rows = RunQuery.run("carmencita-1000", "carmencita-1499", 9, useIndex, shared, out);
			assertTrue(rows > 0, "query must produce rows for the cleanup check to be meaningful");

			assertTrue(BASE_FILES.containsAll(shared.bufferedFileIds()),
					"only base-table/index pages may remain in the pool, found: "
							+ shared.bufferedFileIds());
			assertEquals(BASE_FILES, shared.catalogFileNames(),
					"catalog must hold exactly the base entries after the query");
			assertEquals(0, shared.getTotalPinCount(),
					"all pages must be unpinned after the query closes");
		}

		// the materialized temp table's backing file must be gone from disk
		File cwd = new File(".");
		String[] leftovers = cwd.list((dir, name) -> name.startsWith(".redb-query-"));
		assertNotNull(leftovers);
		assertEquals(0, leftovers.length,
				"no per-query temp files may remain on disk: " + String.join(", ", leftovers));
	}
}
