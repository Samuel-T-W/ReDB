package buffer;

import static org.junit.jupiter.api.Assertions.*;

import catalog.TableEntry;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import storage.Page;
import storage.RawPage;

/**
 * Concurrency tests for the BufferManager's out-of-lock disk I/O paths:
 * exactly one disk read per racing cold page, pin counts that balance under
 * concurrent churn, and no stale reads across in-flight eviction flushes.
 */
public class BufferManagerConcurrencyTest {

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();

	static {
		SCHEMA.put("movieId", 9);
		SCHEMA.put("title", 30);
	}

	/** Creates a temp heap file of {@code numPages} pages, page i filled with byte i. */
	private static String createFingerprintFile(int numPages) throws IOException {
		File tempFile = File.createTempFile("bmConcurrency", ".dat");
		tempFile.deleteOnExit();
		try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
			for (int pageId = 0; pageId < numPages; pageId++) {
				byte[] data = new byte[RawPage.MAX_PAGE_LEN];
				Arrays.fill(data, (byte) pageId);
				raf.seek(RawPage.getOffset(pageId));
				raf.write(data);
			}
		}
		return tempFile.getAbsolutePath();
	}

	/** Runs all tasks at once (released by a shared latch) and rethrows any worker failure. */
	private static void runAllAtOnce(List<? extends Runnable> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (Runnable task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					task.run();
					return null;
				}));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get(); // rethrows worker exceptions in the test thread
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/** A manager whose eviction flush can be stalled and failed on demand. */
	private static final class ControlledFlushManager extends BufferManager {
		final CountDownLatch flushStarted = new CountDownLatch(1);
		final CountDownLatch releaseFlush = new CountDownLatch(1);
		volatile boolean failFlush = false;

		ControlledFlushManager(int bufferSize) { super(bufferSize); }

		@Override
		void writePageToDisk(String fileId, Page page) throws IOException {
			flushStarted.countDown();
			try {
				releaseFlush.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (failFlush)
				throw new IOException("injected flush failure");
			super.writePageToDisk(fileId, page);
		}
	}

	// Starts a getPage on its own thread, recording byte 0 of the page into sink.
	private static Thread startGet(BufferManager bm, String fileName, int pageId, byte[] sink) {
		Thread thread = new Thread(() -> {
			try {
				Page page = bm.getPage(fileName, pageId);
				if (sink != null) {
					sink[0] = page.getByteArray()[0];
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		thread.start();
		return thread;
	}

	@Test
	public void testReaderOfMidFlushPageWaitsInsteadOfReadingStaleDisk() throws Exception {
		// Worked example: page 0 reads 0x00 on disk and is dirtied to 0x2A in
		// memory. While that 0x2A is mid-write, a reader of page 0 must not see
		// the 0x00 still on disk: it finds the FLUSHING frame and waits for it.
		String fileName = createFingerprintFile(3);
		ControlledFlushManager bm = new ControlledFlushManager(2);
		bm.register(new TableEntry(fileName, SCHEMA));
		Page page = bm.getPage(fileName, 0);
		page.getByteArray()[0] = (byte) 0x2A;
		bm.markDirty(fileName, 0);
		bm.unpinPage(fileName, 0);
		bm.getPage(fileName, 1); // fills the other frame, leaving it evictable
		bm.unpinPage(fileName, 1);

		// the clock hand reaches page 0 first, so loading page 2 flushes it
		Thread evictor = startGet(bm, fileName, 2, null);
		assertTrue(bm.flushStarted.await(5, TimeUnit.SECONDS), "flush must start");
		assertEquals(0, bm.getPinCount(fileName, 0), "the victim stays in the page table");

		byte[] seen = new byte[1];
		Thread reader = startGet(bm, fileName, 0, seen);
		reader.join(200);
		assertTrue(reader.isAlive(), "reader must wait for the flush to land");
		bm.releaseFlush.countDown();
		reader.join(5000);
		evictor.join(5000);
		assertEquals((byte) 0x2A, seen[0], "reader must see the flushed bytes");
	}

	@Test
	public void testForceWaitsForMidFlushRatherThanSkippingDurability() throws Exception {
		// force() used to skip FLUSHING frames and return, so a crash (or a
		// later flush failure) could lose dirty bytes it claimed to persist.
		// It must park until the in-flight write settles; the evictor itself
		// makes the page durable on success.
		String fileName = createFingerprintFile(3);
		ControlledFlushManager bm = new ControlledFlushManager(2);
		bm.register(new TableEntry(fileName, SCHEMA));
		Page page = bm.getPage(fileName, 0);
		page.getByteArray()[0] = (byte) 0x2A;
		bm.markDirty(fileName, 0);
		bm.unpinPage(fileName, 0);
		bm.getPage(fileName, 1);
		bm.unpinPage(fileName, 1);

		Thread evictor = startGet(bm, fileName, 2, null);
		assertTrue(bm.flushStarted.await(5, TimeUnit.SECONDS), "flush must start");

		Thread forcer = new Thread(() -> {
			try {
				bm.force();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		forcer.start();
		forcer.join(200);
		assertTrue(forcer.isAlive(), "force must wait for the in-flight flush");
		bm.releaseFlush.countDown();
		evictor.join(5000);
		forcer.join(5000);
		assertFalse(forcer.isAlive(), "force must finish after the flush settles");
		try (RandomAccessFile raf = new RandomAccessFile(fileName, "r")) {
			raf.seek(RawPage.getOffset(0));
			assertEquals((byte) 0x2A, raf.readByte(), "the dirty bytes must be on disk");
		}
	}

	@Test
	public void testFailedFlushLeavesLatestBytesReachableInMemory() throws Exception {
		// Worked example: page 0 is dirtied to 0x2A and its flush fails. The
		// evicting caller gets the IOException, and page 0 still reads back 0x2A
		// from memory, with no disk read: memory holds the only copy.
		String fileName = createFingerprintFile(2);
		ControlledFlushManager bm = new ControlledFlushManager(1);
		bm.register(new TableEntry(fileName, SCHEMA));
		bm.failFlush = true;
		bm.releaseFlush.countDown();
		Page page = bm.getPage(fileName, 0);
		page.getByteArray()[0] = (byte) 0x2A;
		bm.markDirty(fileName, 0);
		bm.unpinPage(fileName, 0);

		assertThrows(IOException.class, () -> bm.getPage(fileName, 1));
		bm.resetIOCounts();
		assertEquals((byte) 0x2A, bm.getPage(fileName, 0).getByteArray()[0], "dirty bytes survive");
		assertEquals(0, bm.getReadIOCount(), "the page must come from memory, not disk");
		bm.unpinPage(fileName, 0);
	}

	/** A manager whose disk read can be stalled on demand. */
	private static final class ControlledLoadManager extends BufferManager {
		final CountDownLatch loadStarted = new CountDownLatch(1);
		final CountDownLatch releaseLoad = new CountDownLatch(1);

		ControlledLoadManager(int bufferSize) { super(bufferSize); }

		@Override
		RawPage readPageFromDisk(PageKey pageKey) throws IOException {
			loadStarted.countDown();
			try {
				releaseLoad.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return super.readPageFromDisk(pageKey);
		}
	}

	@Test
	public void testReaderOfMidLoadPageWaitsInsteadOfIssuingASecondRead() throws Exception {
		String fileName = createFingerprintFile(1);
		ControlledLoadManager bm = new ControlledLoadManager(2);
		bm.register(new TableEntry(fileName, SCHEMA));

		Thread loader = startGet(bm, fileName, 0, null);
		assertTrue(bm.loadStarted.await(5, TimeUnit.SECONDS), "load must start");

		byte[] seen = new byte[1];
		Thread reader = startGet(bm, fileName, 0, seen);
		reader.join(200);
		assertTrue(reader.isAlive(), "reader must wait for the LOADING frame");
		bm.releaseLoad.countDown();
		reader.join(5000);
		loader.join(5000);
		assertEquals((byte) 0, seen[0]);
		assertEquals(1, bm.getReadIOCount(), "still a single disk read after both return");
	}

	@Test
	public void testColdPageRaceIssuesSingleDiskRead() throws Exception {
		// Goal: N threads missing the same page must produce exactly ONE disk
		// read; the LOADING frame in the page table makes the losers wait.
		final int threads = 16;
		String fileName = createFingerprintFile(2);
		BufferManager bm = new BufferManager(4);
		bm.register(new TableEntry(fileName, SCHEMA));
		bm.resetIOCounts();

		List<Runnable> tasks = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			tasks.add(() -> {
				try {
					Page page = bm.getPage(fileName, 0);
					// every caller must see the loaded bytes, not a blank page
					assertEquals((byte) 0, page.getByteArray()[0]);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
		runAllAtOnce(tasks);

		assertEquals(1, bm.getReadIOCount(), "racing threads must share one disk read");
		// pin counts are per-caller: each of the 16 threads pinned for itself
		assertEquals(threads, bm.getPinCount(fileName, 0));
		for (int t = 0; t < threads; t++) {
			bm.unpinPage(fileName, 0);
		}
		assertEquals(0, bm.getTotalPinCount());
	}

	@Test
	public void testConcurrentPinUnpinBalance() throws Exception {
		// Goal: interleaved getPage/unpinPage from many threads never lose or
		// double-count a pin; the pool is quiescent (all pins zero) afterwards.
		final int threads = 8;
		final int iterations = 300;
		final int numPages = 4;
		String fileName = createFingerprintFile(numPages);
		BufferManager bm = new BufferManager(8);
		bm.register(new TableEntry(fileName, SCHEMA));

		List<Runnable> tasks = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			final long seed = 42L + t;
			tasks.add(() -> {
				Random random = new Random(seed);
				try {
					for (int i = 0; i < iterations; i++) {
						int pageId = random.nextInt(numPages);
						bm.getPage(fileName, pageId);
						bm.unpinPage(fileName, pageId);
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
		runAllAtOnce(tasks);

		assertEquals(0, bm.getTotalPinCount(), "pins must balance to zero");
	}

	@Test
	public void testAllFramesPinnedThrowsForEveryRacingThread() throws Exception {
		// Goal: when no frame can be reclaimed, every caller gets the eviction
		// failure rather than hanging on a load that will never install.
		final int threads = 4;
		String fileName = createFingerprintFile(4);
		BufferManager bm = new BufferManager(3);
		bm.register(new TableEntry(fileName, SCHEMA));

		for (int pageId = 0; pageId < 3; pageId++) {
			bm.getPage(fileName, pageId); // pinned, never unpinned
		}

		List<Runnable> tasks = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			tasks.add(() -> {
				RuntimeException ex = assertThrows(RuntimeException.class,
						() -> bm.getPage(fileName, 3));
				assertEquals("All frames are pinned, cannot evict", ex.getMessage());
			});
		}
		runAllAtOnce(tasks);
	}

	@Test
	public void testInvariantsHoldAfterConcurrentChurn() throws Exception {
		// Goal: after heavy concurrent get/dirty/unpin churn with constant
		// eviction, pool accounting still holds: no lost frames, no pin leaks,
		// and every page still carries its fingerprint (no stale or torn loads).
		// The pool must have at least one frame per thread: each thread holds at
		// most one pinned (or mid-load claimed) frame at a time, so with fewer
		// frames than threads "all frames pinned" is a legitimate outcome, not
		// a bug. numPages > poolSize keeps eviction constant regardless.
		final int threads = 8;
		final int iterations = 400;
		final int numPages = 12;
		final int poolSize = 8;
		String fileName = createFingerprintFile(numPages);
		BufferManager bm = new BufferManager(poolSize);
		bm.register(new TableEntry(fileName, SCHEMA));

		List<Runnable> tasks = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			final long seed = 7L + t;
			tasks.add(() -> {
				Random random = new Random(seed);
				try {
					for (int i = 0; i < iterations; i++) {
						int pageId = random.nextInt(numPages);
						Page page = bm.getPage(fileName, pageId);
						assertEquals((byte) pageId, page.getByteArray()[8],
								"page content must match its fingerprint");
						if (random.nextBoolean()) {
							// content is unchanged, so the eviction flush writes
							// back identical bytes; this just exercises the
							// out-of-lock dirty flush path
							bm.markDirty(fileName, pageId);
						}
						bm.unpinPage(fileName, pageId);
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
		runAllAtOnce(tasks);

		assertEquals(0, bm.getTotalPinCount(), "pins must balance to zero");
		int usedFrames = bm.listPageID().length;
		assertTrue(usedFrames <= poolSize, "pageTable can never exceed the pool");
		assertEquals(poolSize, bm.getFreeFrameCount() + usedFrames,
				"free frames plus used frames must account for the whole pool");
	}

	/** A manager that holds the hit path's stale-index window open on demand. */
	private static final class ControlledHitManager extends BufferManager {
		final CountDownLatch reachedWindow = new CountDownLatch(1);
		final CountDownLatch releaseWindow = new CountDownLatch(1);
		volatile boolean arm = false;

		ControlledHitManager(int bufferSize) { super(bufferSize); }

		@Override
		void afterPageTableRead() {
			if (!arm) {
				return;
			}
			arm = false; // only the first caller through the window is held
			reachedWindow.countDown();
			try {
				releaseWindow.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Test
	public void testCacheHitNeverServesAPageTheCallerDidNotAskFor() throws Exception {
		// The lock-free hit path reads the page table without the lock, so the
		// frame index it gets back is only a hint: by the time it is used, that
		// frame may have been evicted and refilled with an entirely different
		// page. Nothing in the state word tells the two apart — the frame is
		// VALID either way, and pinning at the observed version succeeds, because
		// the version observed is the refilled frame's own. Only the frame's
		// pageKey settles it.
		//
		// In production that window is a few instructions wide, so this holds it
		// open explicitly rather than hoping churn lands inside it.
		String fileName = createFingerprintFile(2);
		ControlledHitManager bm = new ControlledHitManager(1);
		bm.register(new TableEntry(fileName, SCHEMA));

		// Frame 0 holds page 0, evictable.
		bm.getPage(fileName, 0);
		bm.unpinPage(fileName, 0);

		// A reader asks for page 0, reads the page table, and stops there.
		byte[] seen = new byte[1];
		bm.arm = true;
		Thread reader = startGet(bm, fileName, 0, seen);
		assertTrue(bm.reachedWindow.await(5, TimeUnit.SECONDS), "the reader must reach the window");

		// The one frame is recycled underneath it: page 0 out, page 1 in. The
		// reader's index now points at a frame holding someone else's page.
		bm.getPage(fileName, 1);
		bm.unpinPage(fileName, 1);

		bm.releaseWindow.countDown();
		reader.join(10_000);
		assertFalse(reader.isAlive(), "the reader must finish");

		assertEquals((byte) 0, seen[0],
				"getPage(0) served the contents of another page through a stale frame index");

		// startGet leaves the reader's pin outstanding; releasing it here also
		// checks the pin landed on the frame that really does hold page 0.
		bm.unpinPage(fileName, 0);
		assertEquals(0, bm.getTotalPinCount(), "pins must balance to zero");
	}

	@Test
	public void testNoPageIsEverHeldByTwoFramesAtOnce() throws Exception {
		// Two frames holding one page means two divergent copies: a write
		// through one is invisible to a reader holding the other, and whichever
		// is evicted second overwrites the other's bytes on disk. The page table
		// cannot represent that state, so this reads the frames themselves.
		final int poolSize = 6;
		final int numPages = 40;
		final int threads = 4;
		final int iterations = 3_000;

		String fileName = createFingerprintFile(numPages);
		BufferManager bm = new BufferManager(poolSize);
		bm.register(new TableEntry(fileName, SCHEMA));

		List<Runnable> tasks = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			final long seed = 101L + t;
			tasks.add(() -> {
				Random random = new Random(seed);
				try {
					for (int i = 0; i < iterations; i++) {
						// Cluster the reads so several threads miss on the same
						// cold page at once: that race is what decides which
						// loader installs the mapping.
						int pageId = random.nextInt(8) + 8 * random.nextInt(numPages / 8);
						bm.getPage(fileName, pageId);
						if (random.nextInt(4) == 0) {
							bm.markDirty(fileName, pageId);
						}
						bm.unpinPage(fileName, pageId);
						assertEquals(Set.of(), bm.duplicatelyHeldPages(),
								"a page was resident in two frames at once");
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
		runAllAtOnce(tasks);

		assertEquals(Set.of(), bm.duplicatelyHeldPages());
		assertEquals(0, bm.getTotalPinCount(), "pins must balance to zero");
	}

	@Test
	public void testMarkDirtyRequiresTheCallerToHoldThePage() throws Exception {
		// Readers now pin without the lock, so a lock cannot be what keeps
		// markDirty away from an evictor. The caller's pin is: a pinned frame
		// cannot be claimed for eviction, so nobody can be reading isDirty or
		// writing the page out while the mark lands. Without a pin there is
		// nothing stopping the frame being evicted and refilled first, and the
		// mark would then dirty whichever page has taken its place.
		String fileName = createFingerprintFile(2);
		BufferManager bm = new BufferManager(2);
		bm.register(new TableEntry(fileName, SCHEMA));

		bm.getPage(fileName, 0);
		bm.markDirty(fileName, 0); // held: allowed
		bm.unpinPage(fileName, 0);

		assertThrows(IllegalStateException.class, () -> bm.markDirty(fileName, 0),
				"marking a page the caller does not hold must be refused, not silently applied");
	}

	@Test
	public void testGetPageWaitsForInFlightFlushOfSameKey() throws Exception {
		// Goal: a getPage racing the eviction flush of the same (dirty) page
		// must wait for the FLUSHING frame instead of reading stale bytes from
		// disk. The overlap cannot be forced deterministically without an I/O
		// fault injection hook, so this loops the race; a stale read shows up
		// as a fingerprint from an earlier iteration.
		final int iterations = 300;
		String fileName = createFingerprintFile(3);
		BufferManager bm = new BufferManager(2);
		bm.register(new TableEntry(fileName, SCHEMA));

		for (int iter = 0; iter < iterations; iter++) {
			final byte stamp = (byte) iter;

			// dirty page 0 with this iteration's stamp, then leave it evictable
			Page page = bm.getPage(fileName, 0);
			page.getByteArray()[0] = stamp;
			bm.markDirty(fileName, 0);
			bm.unpinPage(fileName, 0);

			Runnable evictor = () -> {
				try {
					// page 0 is unpinned, so these loads sweep it out of the pool
					for (int pageId = 1; pageId <= 2; pageId++) {
						bm.getPage(fileName, pageId);
						bm.unpinPage(fileName, pageId);
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			};
			Runnable reader = () -> {
				try {
					Page reloaded = bm.getPage(fileName, 0);
					assertEquals(stamp, reloaded.getByteArray()[0],
							"reader must never observe pre-flush bytes");
					bm.unpinPage(fileName, 0);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			};
			runAllAtOnce(List.of(evictor, reader));
		}
	}
}
