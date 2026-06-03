package storage;

/**
 * Page is the storage-level interface: it exposes only page identity and raw
 * bytes. Row-level APIs live in DataPage.
 */
public interface Page {

	/** Returns the page id. */
	int getPid();

	/** Returns the raw page data. */
	byte[] getByteArray();

	/** Overwrites the page data from disk. */
	void fillPageData(byte[] data);
}
