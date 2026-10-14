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
