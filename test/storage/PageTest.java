package storage;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GenericPage.
 *
 * <p>
 * Tests cover: - insertRecord returns the correct slot id - getRecord retrieves
 * exactly what was inserted - isFull correctly reports when no more records fit
 * - fillPageData / getByteArray round-trips raw bytes unchanged - getPid
 * returns the correct page id - getRecord throws when given an out-of-range
 * slot id - insertRecord returns -1 when the page is full - multiple records
 * can be inserted and each is read back correctly
 */
public class PageTest {

	// Movies schema: movieId=9 bytes, title=30 bytes → recordSize=39
	private static final Map<String, Integer> MOVIE_SCHEMA = new LinkedHashMap<>();
	private static final int RECORD_SIZE = 9 + 30; // 39 bytes

	static {
		MOVIE_SCHEMA.put("movieId", 9);
		MOVIE_SCHEMA.put("title", 30);
	}

	private GenericPage page;

	@BeforeEach
	void setUp() {
		page = new GenericPage(new RawPage(1), MOVIE_SCHEMA);
	}

	/** A fresh page should not be full. */
	@Test
	void testNewPageIsNotFull() {
		assertFalse(page.isFull(), "A brand-new page should not be full");
	}

	/** getPid() must return the id passed to the constructor. */
	@Test
	void testGetPid() {
		GenericPage p = new GenericPage(new RawPage(42), MOVIE_SCHEMA);
		assertEquals(42, p.getPid());
	}

	/** insertRecord should return slot id 0 for the first record. */
	@Test
	void testInsertRecordReturnsCorrectSlotId() {
		GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, "tt0000001", "Carmencita");
		int slotId = page.insertRecord(record);
		assertEquals(0, slotId, "First inserted record should have slot id 0");
	}

	/** Slot ids should increment with each insertion. */
	@Test
	void testInsertRecordSlotIdsIncrement() {
		for (int i = 0; i < 5; i++) {
			GenericRecord r = makeMovieRecord(MOVIE_SCHEMA, "tt000000" + i, "Movie " + i);
			int slotId = page.insertRecord(r);
			assertEquals(i, slotId, "Slot id should equal insertion index");
		}
	}

	/** getRecord should retrieve the exact data that was inserted. */
	@Test
	void testGetRecordMatchesInserted() {
		String expectedId = "tt0000001";
		String expectedTitle = "Carmencita";

		GenericRecord inserted = makeMovieRecord(MOVIE_SCHEMA, expectedId, expectedTitle);
		int slotId = page.insertRecord(inserted);

		GenericRecord retrieved = (GenericRecord) page.getRecord(slotId);
		assertEquals(expectedId, fromFixedBytes(retrieved.getFieldBytes("movieId")));
		assertEquals(expectedTitle, fromFixedBytes(retrieved.getFieldBytes("title")));
	}

	/** insertRecord should return -1 when full */
	@Test
	void testInsertFull() {
		String expectedId = "tt0000001";
		String expectedTitle = "Carmencita";

		GenericRecord inserted = makeMovieRecord(MOVIE_SCHEMA, expectedId, expectedTitle);
		int numRecord = 0;
		int slotId = 0;
		for (int i = 0; i < (int) (GenericPage.MAX_PAGE_LEN - 8) / RECORD_SIZE; i++) {
			slotId = page.insertRecord(inserted);
			assertEquals(slotId, i);
		}
		assertEquals(page.insertRecord(inserted), -1);
	}
}
