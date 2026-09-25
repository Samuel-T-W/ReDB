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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import storage.Page;
import storage.RawPage;

/**
 * Misses and mid-flight I/O stay on globalLock. pinPage of a LOADING or
 * FLUSHING page must park there, then return a handle to the settled frame
 * rather than issuing a second read or seeing stale disk bytes.
 */
public class BufferManagerHandleWaitTest {

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();

	static {
		SCHEMA.put("movieId", 9);
		SCHEMA.put("title", 30);
	}

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

	private static final class ControlledFlushManager extends BufferManager {
		final CountDownLatch flushStarted = new CountDownLatch(1);
		final CountDownLatch releaseFlush = new CountDownLatch(1);

		ControlledFlushManager(int bufferSize) { super(bufferSize); }

		@Override
		void writePageToDisk(String fileId, Page page) throws IOException {
			flushStarted.countDown();
			try {
				releaseFlush.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			super.writePageToDisk(fileId, page);
		}
	}

	@Test
	public void pinPageWaitsForALoadingFrameThenReturnsAHandle() throws Exception {
		String file = fingerprintFile(1);
		ControlledLoadManager bm = new ControlledLoadManager(2);
		bm.register(new TableEntry(file, SCHEMA));

		AtomicReference<PageHandle> loaderHandle = new AtomicReference<>();
		Thread loader = new Thread(() -> {
			try {
				loaderHandle.set(bm.pinPage(file, 0));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		loader.start();
		assertTrue(bm.loadStarted.await(5, TimeUnit.SECONDS));

		AtomicReference<PageHandle> waiterHandle = new AtomicReference<>();
		Thread waiter = new Thread(() -> {
			try {
				waiterHandle.set(bm.pinPage(file, 0));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		waiter.start();
		waiter.join(200);
		assertTrue(waiter.isAlive(), "pinPage must wait on the LOADING frame");

		bm.releaseLoad.countDown();
		waiter.join(5_000);
		loader.join(5_000);

		assertEquals(1, bm.getReadIOCount());
		assertEquals((byte) 0, waiterHandle.get().page().getByteArray()[0]);
		assertEquals(loaderHandle.get().frameIndex(), waiterHandle.get().frameIndex());
		assertEquals(loaderHandle.get().version(), waiterHandle.get().version());
		assertEquals(2, bm.getPinCount(file, 0));
		bm.unpinPage(loaderHandle.get());
		bm.unpinPage(waiterHandle.get());
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	public void pinPageWaitsForAFlushingFrameRatherThanReadingStaleDisk() throws Exception {
		String file = fingerprintFile(3);
		ControlledFlushManager bm = new ControlledFlushManager(2);
		bm.register(new TableEntry(file, SCHEMA));

		PageHandle dirty = bm.pinPage(file, 0);
		dirty.page().getByteArray()[0] = (byte) 0x2A;
		bm.markDirty(file, 0);
		bm.unpinPage(dirty);
		PageHandle other = bm.pinPage(file, 1);
		bm.unpinPage(other);

		Thread evictor = new Thread(() -> {
			try {
				bm.unpinPage(bm.pinPage(file, 2));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		evictor.start();
		assertTrue(bm.flushStarted.await(5, TimeUnit.SECONDS));

		AtomicReference<PageHandle> reader = new AtomicReference<>();
		Thread waiter = new Thread(() -> {
			try {
				reader.set(bm.pinPage(file, 0));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		waiter.start();
		waiter.join(200);
		assertTrue(waiter.isAlive(), "pinPage must wait on the FLUSHING frame");

		bm.releaseFlush.countDown();
		waiter.join(5_000);
		evictor.join(5_000);

		assertEquals((byte) 0x2A, reader.get().page().getByteArray()[0]);
		bm.unpinPage(reader.get());
		assertEquals(0, bm.getTotalPinCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	private static String fingerprintFile(int numPages) throws IOException {
		File temp = File.createTempFile("bmHandleWait", ".dat");
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
