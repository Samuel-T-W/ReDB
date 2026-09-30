package testutil;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import buffer.BufferManager;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import storage.GenericRecord;
import storage.RawPage;

public class TestUtilsWritePagesTest {

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();

	static {
		SCHEMA.put("movieId", 9);
		SCHEMA.put("title", 30);
	}

	@Test
	public void writePagesUnpinsEveryCreatedHandle() throws Exception {
		File tmp = File.createTempFile("writePages", ".dat");
		tmp.deleteOnExit();
		BufferManager bm = new BufferManager(4);

		int pages = writePages(bm, tmp.getAbsolutePath(), SCHEMA,
				List.of(makeMovieRecord(SCHEMA, "tt0000001", "One")));
		assertEquals(1, pages);
		assertEquals(0, bm.getTotalPinCount());
		assertTrue(bm.getLockFreeUnpinCount() > 0);
	}

	@Test
	public void writePagesThatSpillStillLeaveNoPins() throws Exception {
		File tmp = File.createTempFile("writePagesSpill", ".dat");
		tmp.deleteOnExit();
		BufferManager bm = new BufferManager(4);
		int capacity = (RawPage.MAX_PAGE_LEN - 4) / 39;
		List<GenericRecord> records = new ArrayList<>();
		for (int i = 0; i < capacity + 2; i++) {
			records.add(makeMovieRecord(SCHEMA, String.format("tt%07d", i), "T" + i));
		}

		int pages = writePages(bm, tmp.getAbsolutePath(), SCHEMA, records);
		assertTrue(pages >= 2);
		assertEquals(0, bm.getTotalPinCount());
		assertTrue(bm.getLockFreeUnpinCount() >= pages);
	}
}
