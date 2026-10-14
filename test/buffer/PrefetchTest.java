package buffer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
		bm.setPrefetchExecutor(Runnable::run);
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
		bm.setPrefetchExecutor(Runnable::run);
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
		bm.setPrefetchExecutor(Runnable::run);
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
		bm.setPrefetchExecutor(Runnable::run);
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
		bm.setPrefetchExecutor(Runnable::run);
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
		bm.setPrefetchExecutor(Runnable::run);
		FrameBudget budget = new FrameBudget(1);
		try (var scope = FrameBudget.install(budget)) {
			assertDoesNotThrow(() -> bm.prefetch(file, 3));
			assertDoesNotThrow(() -> bm.prefetch(dir.resolve("missing").toString(), 0));
		}
		assertEquals(2, bm.getPrefetchFailed());
		assertEquals(0, budget.charged());
		assertArrayEquals(new int[0], bm.listPageID());
		assertEquals(1, bm.getFreeFrameCount());
		try (var worker = Executors.newSingleThreadExecutor(); var scope = FrameBudget.install(budget)) {
			bm.setPrefetchExecutor(worker);
			bm.prefetch(dir.resolve("missing").toString(), 0);
			worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
			bm.setPrefetchExecutor(task -> { throw new RejectedExecutionException(); });
			assertDoesNotThrow(() -> bm.prefetch("unused", 0));
			assertEquals(4, bm.getPrefetchFailed());
			assertEquals(0, budget.charged());
		}
	}

	@Test
	void asyncPrefetchReturnsBeforeReadAndPinWaitsForPublication() throws Exception {
		String file = pages();
		CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
		CountDownLatch pinStarted = new CountDownLatch(1);
		BufferManager bm = new BufferManager(2) {
			@Override RawPage readPageFromDisk(PageKey key) throws IOException {
				assertNull(FrameBudget.current());
				reading.countDown();
				try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
				catch (InterruptedException e) { throw new IOException(e); }
				return super.readPageFromDisk(key);
			}
			@Override void afterPageTableRead() { pinStarted.countDown(); }
		};
		FrameBudget budget = new FrameBudget(2);
		try (var worker = Executors.newFixedThreadPool(2); var scope = FrameBudget.install(budget)) {
			bm.setPrefetchExecutor(worker);
			try {
				bm.prefetch(file, 0);
				assertTrue(reading.await(5, TimeUnit.SECONDS));
				assertEquals(0, bm.getReadIOCount());
				assertEquals(1, budget.charged());
				var pin = worker.submit(() -> {
					try (var pinScope = FrameBudget.install(budget)) { return bm.pinPage(file, 0); }
				});
				assertTrue(pinStarted.await(5, TimeUnit.SECONDS));
				assertThrows(TimeoutException.class, () -> pin.get(100, TimeUnit.MILLISECONDS));
				assertEquals(2, budget.charged());
				release.countDown();
				PageHandle handle = pin.get(5, TimeUnit.SECONDS);
				assertEquals(1, bm.getReadIOCount());
				assertEquals(1, bm.getPrefetchUsed());
				assertEquals(1, budget.charged());
				bm.unpinPage(handle);
				assertEquals(0, budget.charged());
			} finally { release.countDown(); }
		}
	}

	@Test
	void defaultExecutorLoadsPageThenPinIsAHit() throws Exception {
		BufferManager bm = new BufferManager(1);
		String file = pages();
		bm.prefetch(file, 0);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (bm.getPrefetchLoaded() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
		assertEquals(1, bm.getPrefetchLoaded());
		PageHandle pin = bm.pinPage(file, 0);
		assertEquals(1, bm.getLockFreeHitCount());
		assertEquals(1, bm.getReadIOCount());
		bm.unpinPage(pin);
	}
}
