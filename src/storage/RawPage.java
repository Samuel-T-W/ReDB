package storage;

/**
 * Concrete implementation of the Page interface. Owns the page identity (pid)
 * and the raw byte array. Higher-level page types (GenericPage, LeafIndexPage,
 * etc.) compose a RawPage rather than reimplementing these storage-level
 * concerns.
 */
public class RawPage implements Page {

	public static final int MAX_PAGE_LEN = 4096;

	/**
	 * Largest number of pages one file can hold. Byte offsets are 64-bit, so the
	 * binding constraint is that page ids and page counts are both signed 32-bit
	 * ints: valid ids run 0..MAX_PAGE_COUNT-1.
	 */
	public static final int MAX_PAGE_COUNT = Integer.MAX_VALUE;

	/** Largest addressable file size, ~8.8 TB at 4 KB pages. */
	public static final long MAX_FILE_LEN = (long) MAX_PAGE_COUNT * MAX_PAGE_LEN;

	private final int pid;
	private byte[] data;

	public RawPage(int pid) {
		this.pid = pid;
		this.data = new byte[MAX_PAGE_LEN];
	}

	@Override
	public int getPid() {
		return this.pid;
	}

	@Override
	public byte[] getByteArray() {
		return this.data;
	}

	@Override
	public void fillPageData(byte[] data) {
		this.data = data;
	}

	/**
	 * Returns the byte offset of the given page id within a file.
	 *
	 * <p>The result is a {@code long} so {@code pid * MAX_PAGE_LEN} does not
	 * overflow a signed 32-bit int at 2 GiB (page id 524288).
	 *
	 * <p>An id outside 0..MAX_PAGE_COUNT-1 is unaddressable; a negative one means
	 * the 32-bit page id space wrapped. Either would seek to the wrong place and
	 * silently read or write the wrong bytes, so it fails here instead.
	 */
	public static long getOffset(int pid) {
		if (pid < 0 || pid >= MAX_PAGE_COUNT) {
			throw new IllegalStateException("Page id " + pid + " is outside the addressable range 0.."
					+ (MAX_PAGE_COUNT - 1) + " (a negative id means the page id space wrapped). Byte offsets are "
					+ "64-bit, but page ids are signed 32-bit ints, so a file holds at most " + MAX_FILE_LEN
					+ " bytes (~8.8 TB) at " + MAX_PAGE_LEN + "-byte pages.");
		}
		return (long) pid * MAX_PAGE_LEN;
	}

	/**
	 * Converts a file length in bytes to the number of pages it holds.
	 *
	 * @throws IllegalStateException
	 *             if the length is not a whole number of pages, or if the file
	 *             holds more pages than a 32-bit page id can address
	 */
	public static int pageCount(String fileId, long fileLength) {
		if (fileLength % MAX_PAGE_LEN != 0) {
			throw new IllegalStateException(
					"File size is not a multiple of pages: " + fileId + " is " + fileLength + " bytes");
		}
		if (fileLength > MAX_FILE_LEN) {
			throw new IllegalStateException("File is too large to address: " + fileId + " is " + fileLength
					+ " bytes, over the " + MAX_FILE_LEN + "-byte (~8.8 TB) cap. Byte offsets are 64-bit, but page ids "
					+ "are signed 32-bit ints, so only pages 0.." + (MAX_PAGE_COUNT - 1) + " are reachable.");
		}
		return (int) (fileLength / MAX_PAGE_LEN);
	}
}
