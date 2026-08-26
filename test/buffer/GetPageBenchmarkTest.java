package buffer;

import catalog.TableEntry;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import storage.RawPage;

/**
 * Microbenchmark of a warm getPage hit. Prints p50/p99; does not assert
 * latency, because CI machines vary. Numbers for the lock-free hit path
 * versus the U4a locked path belong in the commit message.
 */
public class GetPageBenchmarkTest {

	private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();

	static {
		SCHEMA.put("movieId", 9);
		SCHEMA.put("title", 30);
	}

	@Test
	public void printWarmGetPageHitPercentiles() throws Exception {
		File tempFile = File.createTempFile("bmBench", ".dat");
		tempFile.deleteOnExit();
		try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
			raf.write(new byte[RawPage.MAX_PAGE_LEN]);
		}
		String fileName = tempFile.getAbsolutePath();
		BufferManager bm = new BufferManager(4);
		bm.register(new TableEntry(fileName, SCHEMA));
		bm.getPage(fileName, 0);
		bm.unpinPage(fileName, 0);

		final int warmup = 20_000;
		final int samples = 50_000;
		for (int i = 0; i < warmup; i++) {
			bm.getPage(fileName, 0);
			bm.unpinPage(fileName, 0);
		}
		long[] ns = new long[samples];
		for (int i = 0; i < samples; i++) {
			long t0 = System.nanoTime();
			bm.getPage(fileName, 0);
			ns[i] = System.nanoTime() - t0;
			bm.unpinPage(fileName, 0);
		}
		Arrays.sort(ns);
		System.out.printf("getPage warm hit p50=%d ns p99=%d ns (n=%d)%n",
				ns[samples / 2], ns[(int) (samples * 0.99)], samples);
	}
}
