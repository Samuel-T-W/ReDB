package buffer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.RawPage;

class FrameBudgetTest {
	@TempDir Path dir;

	@Test
	void closedBudgetRejectsPrefetchAndCloseIsIdempotent() {
		FrameBudget budget = new FrameBudget(3, 1);
		budget.close();
		assertTrue(budget.isClosed());
		assertFalse(budget.tryChargePrefetch());
		assertFalse(budget.beginPrefetch());
		assertEquals(0, budget.charged());
		assertDoesNotThrow(budget::close);
	}

	@Test
	void twoPrefetchesLeaveThreePinsInFiveFrameBudget() {
		FrameBudget budget = new FrameBudget(5, 2);
		assertTrue(budget.tryChargePrefetch());
		assertTrue(budget.tryChargePrefetch());
		assertFalse(budget.tryChargePrefetch());
		for (int i = 0; i < 3; i++) budget.charge();
		assertEquals(5, budget.charged());
		assertThrows(FrameBudgetExceededException.class, budget::charge);
		budget.releasePrefetch();
		budget.charge();
		assertFalse(budget.tryChargePrefetch(), "total limit applies below the reserve too");
		budget.releasePrefetch();
		assertThrows(IllegalStateException.class, budget::releasePrefetch);
		assertEquals(4, budget.charged(), "prefetch underflow must not release a pin charge");
		for (int i = 0; i < 4; i++) budget.release();
		assertEquals(0, budget.charged());
		assertEquals(5, budget.peakCharged());
	}

	@Test
	void defaultReserveIsZeroAndInvalidReservesAreRejected() {
		assertFalse(new FrameBudget(3).tryChargePrefetch());
		assertThrows(IllegalArgumentException.class, () -> new FrameBudget(3, -1));
		assertThrows(IllegalArgumentException.class, () -> new FrameBudget(3, 4));
	}

	@Test
	void twoPinsRejectThirdThenUnpinMakesRoom() throws Exception {
		String file = dir.resolve("pages").toString();
		Files.write(Path.of(file), new byte[RawPage.MAX_PAGE_LEN]);
		BufferManager bm = new BufferManager(4);
		FrameBudget budget = new FrameBudget(2);
		try (var scope = FrameBudget.install(budget)) {
			PageHandle first = bm.pinPage(file, 0);
			bm.getPage(file, 0);
			assertEquals(2, budget.charged());
			assertThrows(FrameBudgetExceededException.class, () -> bm.pinPage(file, 0));
			assertEquals(2, bm.getPinCount(file, 0));
			assertThrows(FrameBudgetExceededException.class, () -> bm.createPinnedPage(file, null));
			bm.unpinPage(first);
			RawPage created = bm.createPage(file, null);
			assertEquals(2, budget.charged());
			bm.unpinPage(file, created.getPid());
			bm.unpinPage(file, 0);
		}
		assertEquals(0, budget.charged());
		assertNull(FrameBudget.current());
	}

	@Test
	void chargeReleaseTracksPeakAndRejectsUnderflow() {
		FrameBudget budget = new FrameBudget(2);
		budget.charge();
		assertTrue(budget.tryCharge());
		assertFalse(budget.tryCharge());
		assertEquals("Frame budget exceeded: limit=2, charged=2",
				assertThrows(FrameBudgetExceededException.class, budget::charge).getMessage());
		budget.release();
		budget.release();
		assertThrows(IllegalStateException.class, budget::release);
		assertEquals(0, budget.charged());
		assertEquals(2, budget.peakCharged());
		assertEquals(2, budget.limit());
	}

	@Test
	void handleUnpinOnAnotherThreadReleasesOriginalBudget() throws Exception {
		BufferManager bm = new BufferManager(2);
		FrameBudget budget = new FrameBudget(1);
		try (var scope = FrameBudget.install(budget); var worker = Executors.newSingleThreadExecutor()) {
			PageHandle handle = bm.createPinnedPage(dir.resolve("created").toString(), null);
			worker.submit(() -> {
				assertNull(FrameBudget.current());
				bm.unpinPage(handle);
			}).get();
			assertEquals(0, budget.charged());
		}
	}

	@Test
	void failedPinRollsBackAndUnboundPinsAreUncharged() throws Exception {
		BufferManager bm = new BufferManager(1);
		FrameBudget budget = new FrameBudget(1);
		String file = dir.resolve("missing").toString();
		try (var scope = FrameBudget.install(budget)) {
			assertThrows(IOException.class, () -> bm.pinPage(file, 0));
			assertEquals(0, budget.charged());
		}
		PageHandle handle = bm.createPinnedPage(file, null);
		bm.getPage(file, 0);
		assertNull(handle.budget());
		bm.unpinPage(handle);
		bm.unpinPage(file, 0);
	}
}
