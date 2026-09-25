package operators;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import buffer.BufferManager;
import catalog.IndexEntry;
import catalog.TableEntry;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import storage.BTreeManager;
import storage.GenericRecord;
import storage.K;
import storage.RecordId;

public class IndexScanTest {

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();
	private static final int KEY_SIZE = 9;

	static {
		SCHEMA.put("movieId", KEY_SIZE);
		SCHEMA.put("title", 30);
	}

	private BufferManager bm;
	private String tableFile;
	private String indexFile;
	private BTreeManager btree;

	@BeforeEach
	void setup() throws Exception {
		tableFile = File.createTempFile("idxscan", ".db").getAbsolutePath();
		indexFile = File.createTempFile("idxscan", ".idx").getAbsolutePath();
		bm = new BufferManager(16);
		bm.register(new TableEntry(tableFile, SCHEMA));
		bm.register(new IndexEntry(indexFile, KEY_SIZE));
		btree = new BTreeManager(5, indexFile, bm, KEY_SIZE);
	}

	@AfterEach
	void cleanup() {
		new File(tableFile).delete();
		new File(indexFile).delete();
	}

	@Test
	void indexScanReturnsTheIndexedRecordAndUnpinsByHandle() throws Exception {
		GenericRecord rec = makeMovieRecord(SCHEMA, "tt0000001", "The Movie");
		writePages(bm, tableFile, SCHEMA, List.of(rec));
		btree.insert(fixedAsciiKey("tt0000001", KEY_SIZE), new RecordId(0, 0));

		K key = fixedAsciiKey("tt0000001", KEY_SIZE);
		IndexScan scan = new IndexScan(bm, tableFile, SCHEMA, btree, key, key);
		scan.open();
		GenericRecord found = scan.next();
		assertNotNull(found);
		assertEquals("The Movie", fromFixedBytes(found.getFieldBytes("title")));
		assertNull(scan.next());
		scan.close();
		assertEquals(0, bm.getTotalPinCount());
	}

	@Test
	void residentDataPageUnpinGoesThroughTheHandle() throws Exception {
		GenericRecord rec = makeMovieRecord(SCHEMA, "tt0000001", "The Movie");
		writePages(bm, tableFile, SCHEMA, List.of(rec));
		btree.insert(fixedAsciiKey("tt0000001", KEY_SIZE), new RecordId(0, 0));

		K key = fixedAsciiKey("tt0000001", KEY_SIZE);
		IndexScan warmup = new IndexScan(bm, tableFile, SCHEMA, btree, key, key);
		warmup.open();
		warmup.next();
		warmup.close();

		bm.resetIOCounts();
		IndexScan scan = new IndexScan(bm, tableFile, SCHEMA, btree, key, key);
		scan.open();
		assertNotNull(scan.next());
		scan.close();

		assertEquals(0, bm.getReadIOCount());
		assertTrue(bm.getLockFreeUnpinCount() > 0,
				"the data-page unpin must use the handle, not the key");
		assertEquals(0, bm.getTotalPinCount());
	}
}
