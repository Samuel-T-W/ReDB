package buffer;

import static org.junit.jupiter.api.Assertions.*;

import catalog.TableEntry;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import storage.RawPage;

/**
 * The same acquisition and contention counters can sit on PR 31's locked
 * path and on this handle path. Only the numbers change. Misses and
 * key-based unpin still take globalLock; a resident handle pair must not.
 */
public class BufferManagerLockStatsTest {

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();

	static {
		SCHEMA.put("movieId", 9);
		SCHEMA.put("title", 30);
	}

	@Test
	public void residentHandleHitAndUnpinTakeNoGlobalLock() throws Exception {
		String file = fingerprintFile(2);
		BufferManager bm = new BufferManager(2);
		bm.register(new TableEntry(file, SCHEMA));

		PageHandle warm = bm.pinPage(file, 0);
		bm.unpinPage(warm);
		bm.resetIOCounts();

		PageHandle hit = bm.pinPage(file, 0);
		bm.unpinPage(hit);

		assertEquals(0, bm.getReadIOCount());
		assertEquals(1, bm.getLockFreeHitCount());
		assertEquals(1, bm.getLockFreeUnpinCount());
		assertEquals(0, bm.getGlobalLockAcquisitions(),
				"steady-state cached hit/unpin must not acquire globalLock");
		assertEquals(0, bm.getGlobalLockContentions());
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void missesAndKeyBasedUnpinStillTakeTheGlobalLock() throws Exception {
		String file = fingerprintFile(2);
		BufferManager bm = new BufferManager(2);
		bm.register(new TableEntry(file, SCHEMA));
		bm.resetIOCounts();

		bm.getPage(file, 0);
		assertTrue(bm.getGlobalLockAcquisitions() > 0, "a cold miss must take globalLock");
		long afterMiss = bm.getGlobalLockAcquisitions();

		bm.unpinPage(file, 0);
		assertTrue(bm.getGlobalLockAcquisitions() > afterMiss,
				"key-based unpin still looks the page up under globalLock");
		assertEquals(0, bm.getLockFreeUnpinCount());
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void createPageIsAStructuralMutationAndTakesTheLock() throws Exception {
		BufferManager bm = new BufferManager(1);
		bm.resetIOCounts();
		bm.createPage("scratch", null);
		assertTrue(bm.getGlobalLockAcquisitions() > 0);
		bm.unpinPage("scratch", 0);
		assertEquals(List.of(), bm.checkInvariants());
	}

	private static String fingerprintFile(int numPages) throws IOException {
		File temp = File.createTempFile("bmLockStats", ".dat");
		temp.deleteOnExit();
		try (RandomAccessFile raf = new RandomAccessFile(temp, "rw")) {
			for (int pageId = 0; pageId < numPages; pageId++) {
				byte[] data = new byte[RawPage.MAX_PAGE_LEN];
				Arrays.fill(data, (byte) pageId);
				raf.seek(RawPage.getOffset(pageId));
				raf.write(data);
			}
		}
		return temp.getAbsolutePath();
	}
}
