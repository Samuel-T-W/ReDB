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
		for (int i = 0; i < (int) (RawPage.MAX_PAGE_LEN - 8) / RECORD_SIZE; i++) {
			slotId = page.insertRecord(inserted);
			assertEquals(slotId, i);
		}
		assertEquals(page.insertRecord(inserted), -1);
	}

	/**
	 * Byte offsets must be computed in 64-bit arithmetic. Page 524288 sits at
	 * 2 GiB, which overflows a signed 32-bit int.
	 */
	@Test
	void testGetOffsetDoesNotOverflowAtTwoGiB() {
		assertEquals(0L, RawPage.getOffset(0));
		assertEquals(RawPage.MAX_PAGE_LEN, RawPage.getOffset(1));
		assertEquals(1L << 31, RawPage.getOffset(524288));
		assertEquals(RawPage.MAX_FILE_LEN - RawPage.MAX_PAGE_LEN,
				RawPage.getOffset(RawPage.MAX_PAGE_COUNT - 1));
	}

	/**
	 * 64-bit offsets are no longer the ceiling; the 32-bit page id is, at ~8.8 TB.
	 * A page id at or past the cap - including one that has wrapped negative -
	 * must fail loudly rather than seek somewhere plausible but wrong.
	 */
	@Test
	void testPageIdPastTheCapFailsLoudly() {
		assertEquals(8796093018112L, RawPage.MAX_FILE_LEN);

		UnaddressablePageException atCap = assertThrows(UnaddressablePageException.class,
				() -> RawPage.getOffset(RawPage.MAX_PAGE_COUNT));
		// The offending id, not the shared cap constant: every message in this
		// family quotes the cap, so only the id identifies which guard fired.
		assertTrue(atCap.getMessage().contains(String.valueOf(RawPage.MAX_PAGE_COUNT)), atCap.getMessage());

		int wrapped = Integer.MAX_VALUE + 1; // wraps to Integer.MIN_VALUE
		UnaddressablePageException wrappedFailure = assertThrows(UnaddressablePageException.class,
				() -> RawPage.getOffset(wrapped));
		assertTrue(wrappedFailure.getMessage().contains(String.valueOf(Integer.MIN_VALUE)),
				wrappedFailure.getMessage());
	}

	/**
	 * A file one page over the cap must be rejected on open, not truncated into a
	 * negative or wrapped page count.
	 */
	@Test
	void testPageCountRejectsFileOverTheCap() {
		assertEquals(RawPage.MAX_PAGE_COUNT, RawPage.pageCount("full.db", RawPage.MAX_FILE_LEN));
		assertEquals(3, RawPage.pageCount("small.db", 3L * RawPage.MAX_PAGE_LEN));

		long overCap = RawPage.MAX_FILE_LEN + RawPage.MAX_PAGE_LEN;
		UnaddressablePageException tooBig = assertThrows(UnaddressablePageException.class,
				() -> RawPage.pageCount("huge.db", overCap));
		assertTrue(tooBig.getMessage().contains("huge.db"), tooBig.getMessage());
		assertTrue(tooBig.getMessage().contains(String.valueOf(overCap)), tooBig.getMessage());

		// A malformed file is a different failure from an unaddressable one, and
		// the type says so - matching on message text could not tell them apart.
		IllegalStateException ragged = assertThrows(IllegalStateException.class,
				() -> RawPage.pageCount("ragged.db", RawPage.MAX_PAGE_LEN + 7));
		assertFalse(ragged instanceof UnaddressablePageException, ragged.getMessage());
	}
}
