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
import storage.Page;
import storage.RawPage;

/**
 * The concurrent page table is only as good as the publication order that
 * fills it. These tests pin that order: identity is written before VALID,
 * a refused publish leaves the frame untouched, and a mapping cannot move
 * from one frame to another behind a loader's back.
 */
public class BufferManagerPublicationTest {

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();

	static {
		SCHEMA.put("movieId", 9);
		SCHEMA.put("title", 30);
	}

	@Test
	public void publishValidWritesIdentityBeforeAdvertisingValid() {
		BufferManager bm = new BufferManager(1);
		FrameState word = new FrameState();
		assertTrue(word.tryBeginLoad());
		Frame frame = new Frame(0, word);
		PageKey key = new PageKey("file", 3);
		RawPage page = new RawPage(3);

		bm.publishValid(frame, key, page);

		assertEquals(FrameState.State.VALID, word.state());
		assertEquals(key, frame.pageKey);
		assertSame(page, frame.page);
		assertTrue(frame.hasPage());
	}

	@Test
	public void publishValidRefusesANullIdentityAndLeavesTheFrameUntouched() {
		BufferManager bm = new BufferManager(1);
		FrameState word = new FrameState();
		assertTrue(word.tryBeginLoad());
		Frame frame = new Frame(0, word);
		PageKey key = new PageKey("file", 0);

		assertThrows(IllegalStateException.class, () -> bm.publishValid(frame, key, null));
		assertThrows(IllegalStateException.class, () -> bm.publishValid(frame, null, new RawPage(0)));

		assertEquals(FrameState.State.LOADING, word.state());
		assertNull(frame.page);
		assertNull(frame.pageKey);
		assertFalse(frame.hasPage());
	}

	@Test
	public void installMappingDoesNotOverwriteAWinner() {
		BufferManager bm = new BufferManager(2);
		PageKey key = new PageKey("file", 0);

		assertTrue(bm.installMapping(key, 0));
		assertFalse(bm.installMapping(key, 1), "a later installer must not steal the key");
		assertTrue(bm.installMapping(key, 0), "the winner may re-install its own frame");
	}

	@Test
	public void createPageIsFindableAndAgreesWithTheFrames() throws Exception {
		BufferManager bm = new BufferManager(2);
		RawPage created = bm.createPage("scratch", null);

		assertEquals(List.of(), bm.checkInvariants());
		assertEquals(1, bm.getPinCount("scratch", created.getPid()));
		assertEquals(created.getPid(), bm.getPage("scratch", created.getPid()).getPid());
		bm.unpinPage("scratch", created.getPid());
		bm.unpinPage("scratch", created.getPid());
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void loadedPagePublishesTheSameWay() throws Exception {
		String file = fingerprintFile(2);
		BufferManager bm = new BufferManager(2);
		bm.register(new TableEntry(file, SCHEMA));

		Page loaded = bm.getPage(file, 1);
		assertEquals((byte) 1, loaded.getByteArray()[0]);
		assertEquals(List.of(), bm.checkInvariants());
		assertEquals(1, bm.getPinCount(file, 1));

		bm.unpinPage(file, 1);
		Page hit = bm.getPage(file, 1);
		assertEquals((byte) 1, hit.getByteArray()[0]);
		assertEquals(1, bm.getLockFreeHitCount());
		bm.unpinPage(file, 1);
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	private static String fingerprintFile(int numPages) throws IOException {
		File temp = File.createTempFile("bmPublish", ".dat");
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
