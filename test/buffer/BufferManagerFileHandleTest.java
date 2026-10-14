package buffer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.RawPage;

class BufferManagerFileHandleTest {

	@TempDir Path directory;
	private final BufferManager bm = new BufferManager(1);
	private String file;

	@AfterEach
	void closeHandle() {
		if (file != null) bm.unregister(file);
	}

	@Test
	void dirtyPageEvictionRoundTripsLiteralBytes() throws Exception {
		file = Files.write(directory.resolve("pages"), new byte[8192]).toString();
		var page = bm.getPage(file, 0);
		byte[] input = new byte[4096];
		input[0] = 42;
		input[4095] = 99;
		page.fillPageData(input);
		bm.markDirty(file, 0);
		bm.unpinPage(file, 0);
		bm.unpinPage(bm.pinPage(file, 1));
		byte[] expected = new byte[4096];
		expected[0] = 42;
		expected[4095] = 99;
		assertArrayEquals(expected, bm.getPage(file, 0).getByteArray());
		assertEquals(1, bm.getWriteIOCount());
		assertEquals(3, bm.getReadIOCount());
		assertEquals(1, bm.getChannelOpenCount());
	}

	@Test
	void manyMissesReuseOneChannelAndShortReadsStillFail() throws Exception {
		byte[] bytes = new byte[8192];
		Arrays.fill(bytes, 4096, 8192, (byte) 7);
		file = Files.write(directory.resolve("pages"), bytes).toString();
		for (int i = 0; i < 40; i++) {
			PageHandle handle = bm.pinPage(file, i % 2);
			assertEquals(i % 2 == 0 ? 0 : 7, handle.page().getByteArray()[4095]);
			bm.unpinPage(handle);
		}
		assertEquals(40, bm.getReadIOCount());
		assertEquals(1, bm.getChannelOpenCount());
		assertThrows(EOFException.class, () -> bm.getPage(file, 2));
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	void unregisterAllowsLazyReopen() throws Exception {
		file = Files.write(directory.resolve("pages"), new byte[8192]).toString();
		bm.unpinPage(bm.pinPage(file, 0));
		bm.unregister(file);
		assertArrayEquals(new byte[4096], bm.getPage(file, 1).getByteArray());
		assertEquals(2, bm.getChannelOpenCount());
	}

	@Test
	void deleteFileAllowsSamePathToReturnNewBytes() throws Exception {
		Path path = Files.write(directory.resolve("pages"), new byte[4096]);
		file = path.toString();
		PageHandle original = bm.pinPage(file, 0);
		assertArrayEquals(new byte[4096], original.page().getByteArray());
		bm.unpinPage(original);
		bm.deleteFile(file);
		assertFalse(Files.exists(path));

		byte[] replacement = new byte[4096];
		replacement[0] = 42;
		replacement[4095] = 99;
		Files.write(path, replacement);
		PageHandle reopened = bm.pinPage(file, 0);
		assertArrayEquals(replacement, reopened.page().getByteArray());
		bm.unpinPage(reopened);
		assertEquals(2, bm.getChannelOpenCount());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	void deleteFileRejectsPinnedPageAndLeavesFileOnDisk() throws Exception {
		Path path = Files.write(directory.resolve("pages"), new byte[4096]);
		file = path.toString();
		PageHandle pinned = bm.pinPage(file, 0);
		try {
			assertThrows(IllegalStateException.class, () -> bm.deleteFile(file));
			assertTrue(Files.exists(path));
			assertEquals(1, bm.getTotalPinCount());
			assertEquals(List.of(), bm.checkInvariants());
		} finally {
			bm.unpinPage(pinned);
		}
	}

	@Test
	void readOnlyFileReturnsItsBytes() throws Exception {
		byte[] bytes = new byte[8192];
		Path path = Files.write(directory.resolve("readonly"), bytes);
		file = path.toString();
		var permissions = Files.getPosixFilePermissions(path);
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--r--r--"));
		try {
			byte[] expected = new byte[4096];
			assertArrayEquals(expected, bm.getPage(file, 0).getByteArray());
		} finally {
			Files.setPosixFilePermissions(path, permissions);
		}
	}

	@Test
	void failedOpenKeepsItsExceptionTypeAndLeavesThePoolUsable() throws Exception {
		file = directory.resolve("missing/pages").toString();
		assertThrows(FileNotFoundException.class, () -> bm.getPage(file, 0));
		Files.createDirectory(directory.resolve("missing"));
		assertThrows(FileNotFoundException.class, () -> bm.getPage(file, 0));
		assertFalse(Files.exists(Path.of(file)));
		Files.write(Path.of(file), new byte[4096]);
		assertArrayEquals(new byte[4096], bm.getPage(file, 0).getByteArray());
		assertEquals(List.of(), bm.checkInvariants());
	}

	@Test
	void bystanderSurvivesManyConcurrentInterrupts() throws Exception {
		byte[] bytes = new byte[256 * 4096];
		Arrays.fill(bytes, (byte) 42);
		String sharedFile = Files.write(directory.resolve("shared-pages"), bytes).toString();
		BufferManager shared = new BufferManager(32);
		AtomicBoolean stop = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicInteger bystanderReads = new AtomicInteger();
		Thread[] readers = new Thread[9];
		for (int i = 0; i < readers.length; i++) {
			int reader = i;
			readers[i] = new Thread(() -> {
				int page = reader * 29;
				while (!stop.get()) {
					try {
						PageHandle handle = shared.pinPage(sharedFile, page++ % 256);
						try {
							assertEquals(42, handle.page().getByteArray()[0]);
							assertEquals(42, handle.page().getByteArray()[4095]);
						} finally {
							shared.unpinPage(handle);
						}
						if (reader == 0) bystanderReads.incrementAndGet();
					} catch (Throwable e) {
						if (reader == 0 || !(e instanceof Exception)) {
							failure.compareAndSet(null, e);
							return;
						}
					} finally {
						Thread.interrupted();
					}
				}
			}, reader == 0 ? "bystander" : "victim-" + reader);
			readers[i].setDaemon(true);
		}
		try {
			for (Thread reader : readers) reader.start();
			long deadline = System.nanoTime() + 1_500_000_000L;
			while (System.nanoTime() < deadline && failure.get() == null) {
				for (int i = 1; i < readers.length; i++) readers[i].interrupt();
				LockSupport.parkNanos(50_000);
			}
		} finally {
			stop.set(true);
			long deadline = System.nanoTime() + 1_000_000_000L;
			for (Thread reader : readers) {
				reader.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
			}
			shared.unregister(sharedFile);
		}
		for (Thread reader : readers) assertFalse(reader.isAlive(), reader.getName() + " did not stop");
		assertNull(failure.get(), () -> "reader failed: " + failure.get());
		assertTrue(bystanderReads.get() >= 256, "bystander must complete repeated scans");
		assertTrue(shared.getReadIOCount() > 256, "reads must keep missing the pool");
		assertEquals(List.of(), shared.checkInvariants());
		assertEquals(0, shared.getTotalPinCount());
	}

	@Test
	void interruptedMissFailsThenThePoolAndChannelRecover() throws Exception {
		file = Files.write(directory.resolve("pages"), new byte[8192]).toString();
		bm.unpinPage(bm.pinPage(file, 0));
		Thread.currentThread().interrupt();
		try {
			assertThrows(ClosedByInterruptException.class, () -> bm.getPage(file, 1));
			assertTrue(Thread.currentThread().isInterrupted());
			assertEquals(1, bm.getChannelOpenCount());
		} finally {
			Thread.interrupted();
		}
		assertEquals(List.of(), bm.checkInvariants());
		assertArrayEquals(new byte[4096], bm.getPage(file, 1).getByteArray());
		assertEquals(2, bm.getChannelOpenCount());
	}
}
