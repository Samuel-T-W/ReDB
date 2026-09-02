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
 * pinPage hands back a versioned handle on the existing path — lock-free hit
 * included — while unpin still goes by key. The handle is not consumed yet;
 * these tests prove it names the pin that getPage already took.
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
