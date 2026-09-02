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
 * pinPage hands back a versioned handle; unpinPage(handle) releases that pin
 * on the locked path. Key-based unpin remains for existing callers.
 */
public class BufferManagerHandleTest {

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();

	static {
		SCHEMA.put("movieId", 9);
		SCHEMA.put("title", 30);
	}

	@Test
	public void pinPageReturnsTheRequestedPageAndLeavesItPinned() throws Exception {
		String file = fingerprintFile(2);
		BufferManager bm = new BufferManager(2);
		bm.register(new TableEntry(file, SCHEMA));

		PageHandle handle = bm.pinPage(file, 1);

		assertEquals(file, handle.fileId());
		assertEquals(1, handle.pageId());
		assertEquals((byte) 1, handle.page().getByteArray()[0]);
		assertEquals(1, bm.getPinCount(file, 1));
		assertFalse(handle.isReleased());
		assertEquals(List.of(), bm.checkInvariants());

		bm.unpinPage(file, 1);
		assertEquals(0, bm.getTotalPinCount());
	}

	@Test
	public void twoPinsShareTheSameIncarnation() throws Exception {
		String file = fingerprintFile(1);
		BufferManager bm = new BufferManager(1);
		bm.register(new TableEntry(file, SCHEMA));

		PageHandle first = bm.pinPage(file, 0);
		PageHandle second = bm.pinPage(file, 0);

		assertEquals(first.frameIndex(), second.frameIndex());
		assertEquals(first.version(), second.version());
		assertSame(first.page(), second.page());
		assertEquals(2, bm.getPinCount(file, 0));
		assertEquals(1, bm.getLockFreeHitCount());

		bm.unpinPage(file, 0);
		bm.unpinPage(file, 0);
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void handleUnpinReleasesExactlyThatPin() throws Exception {
		String file = fingerprintFile(1);
		BufferManager bm = new BufferManager(1);
		bm.register(new TableEntry(file, SCHEMA));

		PageHandle first = bm.pinPage(file, 0);
		PageHandle second = bm.pinPage(file, 0);
		assertEquals(2, bm.getPinCount(file, 0));

		bm.unpinPage(first);
		assertTrue(first.isReleased());
		assertEquals(1, bm.getPinCount(file, 0));
		assertThrows(IllegalStateException.class, () -> bm.unpinPage(first),
				"a second unpin of the same handle must not drop the sibling pin");
		assertEquals(1, bm.getPinCount(file, 0));

		bm.unpinPage(second);
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void staleHandleCannotStealARecycledFramePin() throws Exception {
		String file = fingerprintFile(2);
		BufferManager bm = new BufferManager(1);
		bm.register(new TableEntry(file, SCHEMA));

		PageHandle original = bm.pinPage(file, 0);
		int frameIndex = original.frameIndex();
		long oldVersion = original.version();
		PageKey key = original.key();
		bm.unpinPage(original);

		bm.getPage(file, 1);
		bm.unpinPage(file, 1);

		PageHandle reloaded = bm.pinPage(file, 0);
		assertEquals(frameIndex, reloaded.frameIndex());
		assertNotEquals(oldVersion, reloaded.version());
		assertEquals(1, bm.getPinCount(file, 0));

		PageHandle forged = new PageHandle(frameIndex, oldVersion, key, reloaded.page());
		assertThrows(IllegalStateException.class, () -> bm.unpinPage(forged),
				"unpinning a recycled version must not drop the new pin");
		assertEquals(1, bm.getPinCount(file, 0));
		assertEquals((byte) 0, reloaded.page().getByteArray()[0]);

		bm.unpinPage(reloaded);
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void handleUnpinOnAResidentPageDoesNotNeedTheKeyLookup() throws Exception {
		String file = fingerprintFile(1);
		BufferManager bm = new BufferManager(1);
		bm.register(new TableEntry(file, SCHEMA));

		PageHandle warm = bm.pinPage(file, 0);
		bm.unpinPage(warm);
		bm.resetIOCounts();

		PageHandle hit = bm.pinPage(file, 0);
		assertEquals(1, bm.getLockFreeHitCount());
		bm.unpinPage(hit);
		assertEquals(1, bm.getLockFreeUnpinCount());
		assertEquals(0, bm.getReadIOCount());
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void createPinnedPageReturnsAHandleThatUnpinsThatAllocation() throws Exception {
		BufferManager bm = new BufferManager(2);
		PageHandle created = bm.createPinnedPage("scratch", null);

		assertEquals("scratch", created.fileId());
		assertEquals(0, created.pageId());
		assertEquals(1, bm.getPinCount("scratch", 0));
		assertEquals(List.of(), bm.checkInvariants());

		bm.unpinPage(created);
		assertTrue(created.isReleased());
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void createPageStillUnwrapsAndKeyBasedUnpinStillWorks() throws Exception {
		BufferManager bm = new BufferManager(1);
		RawPage page = bm.createPage("scratch", null);
		assertEquals(0, page.getPid());
		assertEquals(1, bm.getPinCount("scratch", 0));
		bm.unpinPage("scratch", 0);
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void getPageStillUnwrapsThePinnedPage() throws Exception {
		String file = fingerprintFile(1);
		BufferManager bm = new BufferManager(1);
		bm.register(new TableEntry(file, SCHEMA));

		assertEquals((byte) 0, bm.getPage(file, 0).getByteArray()[0]);
		assertEquals(1, bm.getPinCount(file, 0));
		bm.unpinPage(file, 0);
		assertEquals((byte) 0, bm.getPage(file, 0).getByteArray()[0]);
		assertEquals(1, bm.getLockFreeHitCount());
		bm.unpinPage(file, 0);
		assertEquals(0, bm.getTotalPinCount());
	}

	private static String fingerprintFile(int numPages) throws IOException {
		File temp = File.createTempFile("bmHandle", ".dat");
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
