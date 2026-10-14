package buffer;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** One lazily opened channel; closed channels are replaced by CAS. */
final class FileHandle {
	private final AtomicReference<FileChannel> channel = new AtomicReference<>();

	FileChannel channel(String fileId, boolean write, LongAdder opens) throws IOException {
		while (true) {
			FileChannel current = channel.get();
			if (current != null && current.isOpen()) return current;
			if (Thread.currentThread().isInterrupted()) throw new ClosedByInterruptException();
			FileChannel opened;
			try {
				try {
					opened = FileChannel.open(Path.of(fileId), write
							? Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
							: Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
				} catch (AccessDeniedException e) {
					if (write) throw e;
					opened = FileChannel.open(Path.of(fileId), StandardOpenOption.READ);
				}
			} catch (IOException e) {
				// RandomAccessFile reported open failures as FileNotFoundException.
				throw (FileNotFoundException) new FileNotFoundException(e.getMessage()).initCause(e);
			}
			opens.increment();
			if (channel.compareAndSet(current, opened)) return opened;
			opened.close();
		}
	}

	void close() throws IOException {
		FileChannel current = channel.getAndSet(null);
		if (current != null) current.close();
	}
}
