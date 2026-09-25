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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import storage.RawPage;

/**
 * The performance gate and the concurrent handle path. Steady-state cached
 * pin/unpin pairs must take zero globalLock acquisitions, do the same
 * logical work (one pin, one unpin, no disk I/O), and leave the pool
 * consistent. The earlier contention diagnosis is not re-used here: these
 * counters are what confirm it.
 */
public class BufferManagerHandleConcurrencyTest {

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();

	static {
		SCHEMA.put("movieId", 9);
		SCHEMA.put("title", 30);
	}

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
				f.get();
			}
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void steadyStateCachedHandlePairsTakeZeroGlobalLocks() throws Exception {
		final int poolSize = 8;
		final int numPages = 4;
		final int threads = 4;
		final int iterations = 5_000;

		String file = fingerprintFile(numPages);
		BufferManager bm = new BufferManager(poolSize);
		bm.register(new TableEntry(file, SCHEMA));
		for (int pageId = 0; pageId < numPages; pageId++) {
			bm.unpinPage(bm.pinPage(file, pageId));
		}
		bm.resetIOCounts();

		List<Runnable> tasks = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			tasks.add(() -> {
				try {
					for (int i = 0; i < iterations; i++) {
						PageHandle handle = bm.pinPage(file, i % numPages);
						assertEquals((byte) (i % numPages), handle.page().getByteArray()[0]);
						bm.unpinPage(handle);
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
		runAllAtOnce(tasks);

		long expected = (long) threads * iterations;
		assertEquals(0, bm.getReadIOCount(), "the working set is resident");
		assertEquals(expected, bm.getLockFreeHitCount());
		assertEquals(expected, bm.getLockFreeUnpinCount());
		assertEquals(0, bm.getGlobalLockAcquisitions(),
				"cached handle hit/unpin pairs must not acquire globalLock");
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void concurrentHandleChurnKeepsPinsBalancedAndInvariantsIntact() throws Exception {
		final int poolSize = 6;
		final int numPages = 16;
		final int threads = 4;
		final int iterations = 1_500;

		String file = fingerprintFile(numPages);
		BufferManager bm = new BufferManager(poolSize);
		bm.register(new TableEntry(file, SCHEMA));

		List<Runnable> tasks = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			final long seed = 17L + t;
			tasks.add(() -> {
				Random random = new Random(seed);
				try {
					for (int i = 0; i < iterations; i++) {
						int pageId = random.nextInt(numPages);
						PageHandle handle = bm.pinPage(file, pageId);
						assertEquals((byte) pageId, handle.page().getByteArray()[8]);
						if (random.nextInt(4) == 0) {
							bm.markDirty(file, pageId);
						}
						bm.unpinPage(handle);
						if (i % 64 == 0) {
							assertEquals(List.of(), bm.checkInvariants());
						}
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
		runAllAtOnce(tasks);

		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
		assertTrue(bm.getLockFreeUnpinCount() > 0);
	}

	private static String fingerprintFile(int numPages) throws IOException {
		File temp = File.createTempFile("bmHandleConc", ".dat");
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
