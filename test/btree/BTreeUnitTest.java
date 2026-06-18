package btree;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import buffer.*;
import catalog.IndexEntry;
import catalog.TableEntry;
import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import storage.*;

/** Unit tests for BTreeManager using small synthetic data (no CSV loading). */
public class BTreeUnitTest {

	private static final String INDEX_FILE = "unit_test.idx";
	private static final String TABLE_FILE = "unit_test.db";

	private static final int KEY_SIZE = 10;
	private static final int BTREE_DEGREE = 5;
	private static final int BUFFER_SIZE = 20;

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();

	static {
		SCHEMA.put("key", KEY_SIZE);
	}

	private BufferManager bm;
	private BTreeManager btree;

	@BeforeEach
	void setup() throws Exception {
		for (String f : new String[]{INDEX_FILE, TABLE_FILE}) {
			File file = new File(f);
			file.delete();
			file.createNewFile();
		}
		bm = new BufferManager(BUFFER_SIZE);
		bm.register(new TableEntry(TABLE_FILE, SCHEMA));
		bm.register(new IndexEntry(INDEX_FILE, KEY_SIZE));
		btree = new BTreeManager(BTREE_DEGREE, INDEX_FILE, bm, KEY_SIZE);
	}

	@AfterEach
	void cleanup() {
		new File(INDEX_FILE).delete();
		new File(TABLE_FILE).delete();
	}

	// -----------------------------------------------------------------------
	// Test 1: Empty-result search
	// -----------------------------------------------------------------------

	@Test
	public void testSearchMissingKeyReturnsEmpty() throws Exception {
		// Insert a handful of keys
		btree.insert(fixedAsciiKey("apple", KEY_SIZE), new RecordId(0, 0));
		btree.insert(fixedAsciiKey("banana", KEY_SIZE), new RecordId(0, 1));
		btree.insert(fixedAsciiKey("cherry", KEY_SIZE), new RecordId(0, 2));

		// Search for a key that was never inserted
		Iterator<RecordId> it = btree.search(fixedAsciiKey("mango", KEY_SIZE));
		assertFalse(it.hasNext(), "search for absent key should return an empty iterator");
	}

	// -----------------------------------------------------------------------
	// Test 2: Duplicate key search
	// -----------------------------------------------------------------------

	@Test
	public void testSearchDuplicateKeysReturnsAllRids() throws Exception {
		K dupKey = fixedAsciiKey("dup", KEY_SIZE);
		RecordId rid0 = new RecordId(0, 0);
		RecordId rid1 = new RecordId(1, 3);
		RecordId rid2 = new RecordId(2, 7);

		btree.insert(dupKey, rid0);
		btree.insert(dupKey, rid1);
		btree.insert(dupKey, rid2);

		// Also insert a different key so the tree has more than one entry
		btree.insert(fixedAsciiKey("other", KEY_SIZE), new RecordId(3, 0));

		Iterator<RecordId> it = btree.search(dupKey);
		int count = 0;
		while (it.hasNext()) {
			it.next();
			count++;
		}
		assertEquals(3, count, "search for duplicate key should return all 3 RIDs");
	}
}
