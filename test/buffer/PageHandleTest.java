package buffer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import storage.RawPage;

public class PageHandleTest {

	@Test
	public void handleExposesThePinnedPageAndItsIdentity() {
		PageKey key = new PageKey("movies.db", 7);
		RawPage page = new RawPage(7);
		PageHandle handle = new PageHandle(3, 11L, key, page);

		assertSame(page, handle.page());
		assertEquals(key, handle.key());
		assertEquals("movies.db", handle.fileId());
		assertEquals(7, handle.pageId());
		assertEquals(3, handle.frameIndex());
		assertEquals(11L, handle.version());
		assertFalse(handle.isReleased());
		assertTrue(handle.toString().contains("frame=3"));
		assertTrue(handle.toString().contains("version=11"));
	}

	@Test
	public void markReleasedSucceedsOnce() {
		PageHandle handle = new PageHandle(0, 0L, new PageKey("f", 0), new RawPage(0));

		assertTrue(handle.markReleased(), "the pin this handle represents is released once");
		assertTrue(handle.isReleased());
		assertFalse(handle.markReleased(), "a second release must not drop a sibling pin");
		assertTrue(handle.isReleased());
	}

	@Test
	public void twoHandlesAreDistinctPinTokensEvenForTheSameIncarnation() {
		PageKey key = new PageKey("f", 1);
		RawPage page = new RawPage(1);
		PageHandle first = new PageHandle(2, 4L, key, page);
		PageHandle second = new PageHandle(2, 4L, key, page);

		assertNotSame(first, second);
		assertTrue(first.markReleased());
		assertFalse(first.markReleased());
		assertTrue(second.markReleased(), "releasing one handle must not consume the other");
	}

	@Test
	public void constructorRejectsAnUnusableHandle() {
		PageKey key = new PageKey("f", 0);
		RawPage page = new RawPage(0);

		assertThrows(IllegalArgumentException.class, () -> new PageHandle(-1, 0L, key, page));
		assertThrows(IllegalArgumentException.class, () -> new PageHandle(0, -1L, key, page));
		assertThrows(NullPointerException.class, () -> new PageHandle(0, 0L, null, page));
		assertThrows(NullPointerException.class, () -> new PageHandle(0, 0L, key, null));
	}
}
