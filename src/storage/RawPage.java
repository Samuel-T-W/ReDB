package storage;

/**
 * Concrete implementation of the Page interface. Owns the page identity (pid)
 * and the raw byte array. Higher-level page types (GenericPage, LeafIndexPage,
 * etc.) compose a RawPage rather than reimplementing these storage-level
 * concerns.
 */
public class RawPage implements Page {

	public static final int MAX_PAGE_LEN = 4096;

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

	/** Returns the byte offset of the given page id within a file. */
	public static int getOffset(int pid) {
		return pid * MAX_PAGE_LEN;
	}
}
