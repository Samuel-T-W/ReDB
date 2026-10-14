package buffer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.RawPage;

class PrefetchTest {
	@TempDir Path dir;

	private String pages() throws Exception {
		return Files.write(dir.resolve("pages"), new byte[3 * RawPage.MAX_PAGE_LEN]).toString();
	}

	@Test
	void prefetchThenPinReadsOnceAndReturnsPageBytes() throws Exception {
		byte[] bytes = new byte[RawPage.MAX_PAGE_LEN];
		bytes[0] = 42;
		String file = Files.write(dir.resolve("example"), bytes).toString();
		BufferManager bm = new BufferManager(2);
		bm.prefetch(file, 0);
		assertEquals(0, bm.getTotalPinCount());
		PageHandle pin = bm.pinPage(file, 0);
		assertEquals(42, pin.page().getByteArray()[0]);
		assertEquals(1, bm.getReadIOCount());
		assertEquals(1, bm.getLockFreeHitCount());
		assertEquals(1, bm.getPrefetchLoaded());
		assertEquals(1, bm.getPrefetchUsed());
		bm.unpinPage(pin);
	}

	@Test
	void residentPrefetchDoesNoIOAndReturnsItsReservation() throws Exception {
		String file = pages();
		BufferManager bm = new BufferManager(2);
		bm.unpinPage(bm.pinPage(file, 0));
		bm.resetIOCounts();
		FrameBudget budget = new FrameBudget(1);
		try (var scope = FrameBudget.install(budget)) { bm.prefetch(file, 0); }
		assertEquals(0, bm.getReadIOCount());
		assertEquals(1, bm.getPrefetchAlreadyResident());
		assertEquals(0, budget.charged());
	}

	@Test
	void fullBudgetRejectsPinWithoutChangingPrefetchCharge() throws Exception {
		String file = pages();
		BufferManager bm = new BufferManager(2);
		FrameBudget budget = new FrameBudget(1);
		try (var scope = FrameBudget.install(budget)) {
			bm.prefetch(file, 0);
			assertEquals(1, budget.charged());
			bm.prefetch(file, 1);
			assertEquals(1, bm.getPrefetchSkippedBudget());
			assertThrows(FrameBudgetExceededException.class, () -> bm.pinPage(file, 0));
			assertEquals(0, bm.getTotalPinCount());
			assertEquals(1, budget.charged());
			assertEquals(0, bm.getPrefetchUsed());
		}
	}

	@Test
	void unbudgetedThreadPinReleasesIssuingBudgetCharge() throws Exception {
		String file = pages();
		BufferManager bm = new BufferManager(2);
		FrameBudget budget = new FrameBudget(2);
		try (var scope = FrameBudget.install(budget); var worker = Executors.newSingleThreadExecutor()) {
			bm.prefetch(file, 0);
			assertEquals(1, budget.charged());
			worker.submit(() -> {
				assertNull(FrameBudget.current());
				bm.unpinPage(bm.pinPage(file, 0));
				return null;
			}).get(5, TimeUnit.SECONDS);
			assertEquals(0, budget.charged());
			assertEquals(1, bm.getPrefetchUsed());
		}
	}

	@Test
	void evictionAndDiscardReleaseUnusedCharges() throws Exception {
		String file = pages();
		BufferManager bm = new BufferManager(1);
		FrameBudget budget = new FrameBudget(1);
		try (var scope = FrameBudget.install(budget)) { bm.prefetch(file, 0); }
		bm.unpinPage(bm.pinPage(file, 1));
		assertEquals(1, bm.getPrefetchEvictedUnused());
		assertEquals(0, budget.charged());
		try (var scope = FrameBudget.install(budget)) { bm.prefetch(file, 0); }
		bm.discardFile(file);
		assertEquals(2, bm.getPrefetchEvictedUnused());
		assertEquals(0, budget.charged());
	}

	@Test
	void failedLoadsReturnChargesAndLeaveNoMapping() throws Exception {
		String file = pages();
		BufferManager bm = new BufferManager(1);
		FrameBudget budget = new FrameBudget(1);
		try (var scope = FrameBudget.install(budget)) {
			assertDoesNotThrow(() -> bm.prefetch(file, 3));
			assertDoesNotThrow(() -> bm.prefetch(dir.resolve("missing").toString(), 0));
		}
		assertEquals(2, bm.getPrefetchFailed());
		assertEquals(0, budget.charged());
		assertArrayEquals(new int[0], bm.listPageID());
		assertEquals(1, bm.getFreeFrameCount());
	}
}
