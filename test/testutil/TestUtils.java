package testutil;

import buffer.BufferManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import storage.GenericPage;
import storage.GenericRecord;
import storage.K;
import util.RecordUtils;

public final class TestUtils {
	private TestUtils() {
	}

	public static byte[] toFixedBytes(String s, int length) {
		return RecordUtils.toFixedBytes(s, length);
	}

	public static String fromFixedBytes(byte[] bytes) {
		int len = bytes.length;
		while (len > 0 && bytes[len - 1] == 0)
			len--;
		return new String(bytes, 0, len, StandardCharsets.US_ASCII);
	}

	public static int fromByteArray(byte[] bytes) {
		return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8)
				| (bytes[3] & 0xFF);
	}

	public static byte[] readBytesFromArray(byte[] src, int offset, int length) {
		if (offset < 0 || length < 0 || offset + length > src.length) {
			throw new IndexOutOfBoundsException("Offset or length out of bounds");
		}

		byte[] dest = new byte[length];
		System.arraycopy(src, offset, dest, 0, length);
		return dest;
	}

	public static GenericRecord makeMovieRecord(Map<String, Integer> schema, String movieId, String title) {
		return GenericRecord.create(schema).set("movieId", toFixedBytes(movieId, schema.get("movieId"))).set("title",
				toFixedBytes(title, schema.get("title")));
	}

	public static int writePages(BufferManager bm, String fileId, Map<String, Integer> schema,
			List<GenericRecord> records) throws IOException {
		GenericPage page = new GenericPage(bm.createPage(fileId, null), schema);
		int pageCount = 1;

		for (GenericRecord rec : records) {
			if (page.isFull()) {
				bm.markDirty(fileId, page.getPid());
				bm.unpinPage(fileId, page.getPid());
				page = new GenericPage(bm.createPage(fileId, null), schema);
				pageCount++;
			}
			page.insertRecord(rec);
		}

		bm.markDirty(fileId, page.getPid());
		bm.unpinPage(fileId, page.getPid());
		bm.force();
		return pageCount;
	}

	public static K fixedAsciiKey(String key, int length) {
		return new K(toFixedBytes(key, length));
	}
}
