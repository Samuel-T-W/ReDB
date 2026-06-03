package storage;

import java.util.Map;

/**
 * A GenericPage has length 4096B. First 4B stores number of records in the
 * page. The rest store records.
 *
 * <p>
 * GenericPage implements DataPage (which extends Page), making it the concrete
 * class that owns the raw byte array. The BufferManager works with the Page
 * interface; callers downcast to DataPage/GenericPage for record-level
 * operations.
 */
public class GenericPage implements DataPage {
	public static final int MAX_PAGE_LEN = 4096;

	/** Number of bytes reserved at the start of every page for metadata. */
	private static final int HEADER_SIZE = 4; // 4 bytes numRecords

	private final Map<String, Integer> schema;
	private final int recordSize;
	private final int pid;
	private byte[] data;

	/**
	 * Wraps an existing Page (e.g. a RawPage from the BufferManager) as a
	 * GenericPage, reusing its pid and byte array.
	 */
	public GenericPage(Page page, Map<String, Integer> schema) {
		this.pid = page.getPid();
		this.data = page.getByteArray();
		this.schema = schema;
		int size = 0;
		for (Map.Entry<String, Integer> field : schema.entrySet()) {
			size += field.getValue();
		}
		this.recordSize = size;
	}

	/**
	 * Convert integer to byte array.
	 *
	 * @param value
	 *            The integer to convert.
	 * @return A byte array represent the integer.
	 */
	private static byte[] toByteArray(int value) {
		return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
	}

	/**
	 * convert byte array to integer, big endian
	 *
	 * @param bytes
	 *            The byte array to convert.
	 * @return An integer represent the byte array.
	 */
	private static int fromByteArray(byte[] bytes) {
		return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8)
				| ((bytes[3] & 0xFF) << 0);
	}

	/**
	 * read some bytes starting from an offset
	 *
	 * @param sourceArray
	 *            The byte array to be read.
	 * @param offset
	 *            The offset to start reading
	 * @param length
	 *            The number of bytes to be read
	 * @return A byte array.
	 */
	private static byte[] readBytesFromArray(byte[] sourceArray, int offset, int length) {
		if (offset < 0 || length < 0 || offset + length > sourceArray.length) {
			throw new IndexOutOfBoundsException("Offset or length out of bounds");
		}

		byte[] destArray = new byte[length];
		// Parameters: src, srcPos, dest, destPos, length
		System.arraycopy(sourceArray, offset, destArray, 0, length);

		return destArray;
	}

	/**
	 * write some bytes starting from an offset
	 *
	 * @param sourceArray
	 *            The byte array.
	 * @param destArray
	 *            The byte array to be written to.
	 * @param offset
	 *            The offset to start writing.
	 * @param length
	 *            The number of bytes to write.
	 * @return A byte array.
	 */
	private static byte[] writeBytesToArray(byte[] sourceArray, byte[] destArray, int offset, int length) {
		if (offset < 0 || length < 0 || offset + length > destArray.length) {
			throw new IndexOutOfBoundsException("Offset or length out of bounds");
		}

		// Parameters: src, srcPos, dest, destPos, length
		System.arraycopy(sourceArray, 0, destArray, offset, length);

		return destArray;
	}

	/** Reads the stored numRecords value from the page header. */
	private int getNumRecords() {
		return fromByteArray(readBytesFromArray(this.data, 0, 4));
	}

	/** Writes a numRecords value into the page header. */
	private void writeNumRecords(int numRecords) {
		writeBytesToArray(toByteArray(numRecords), this.data, 0, 4);
	}

	/**
	 * Fetches a record from the page by its record ID.
	 *
	 * @param slotId
	 *            The ID of the record to retrieve.
	 * @return The Record object containing the data of a row.
	 */
	@Override
	public Record getRecord(int slotId) {
		int numRecords = getNumRecords();
		if (slotId < 0 || slotId >= numRecords) {
			throw new IndexOutOfBoundsException("recordId " + slotId + " out of range (numRecords=" + numRecords + ")");
		}
		int offset = HEADER_SIZE + slotId * this.recordSize;
		byte[] recordData = readBytesFromArray(this.data, offset, this.recordSize);
		return new GenericRecord(this.schema, recordData);
	}

	/**
	 * Inserts a new record into the page.
	 *
	 * @param record
	 *            The Record object containing the data to insert.
	 * @return The record ID of the inserted record, or -1 if the page is full
	 */
	@Override
	public int insertRecord(Record record) {

		if (isFull()) {
			return -1;
		}

		int numRecords = getNumRecords();
		int recordId = numRecords;
		int offset = HEADER_SIZE + recordId * this.recordSize;

		byte[] recordData = ((GenericRecord) record).toByteArray();
		writeBytesToArray(recordData, this.data, offset, this.recordSize);

		writeNumRecords(numRecords + 1);

		return recordId;
	}

	/**
	 * Check if the page is full.
	 *
	 * @return true if the page is full, false otherwise
	 */
	@Override
	public boolean isFull() {

		int numRecords = getNumRecords();
		int usedBytes = HEADER_SIZE + numRecords * this.recordSize;
		int availBytes = MAX_PAGE_LEN - usedBytes;
		return availBytes < this.recordSize;
	}

	/**
	 * Returns the page id
	 *
	 * @return page id of this page
	 */
	@Override
	public int getPid() {
		return this.pid;
	}

	/** Returns the raw page data. */
	@Override
	public byte[] getByteArray() {
		return this.data;
	}

	/** Overwrites the page data from disk. */
	@Override
	public void fillPageData(byte[] data) {
		this.data = data;
	}

	/** Returns the maximum number of records that fit on this page. */
	public int capacity() {

		return (MAX_PAGE_LEN - HEADER_SIZE) / this.recordSize;
	}

	public static int getOffset(int pid) {
		return pid * MAX_PAGE_LEN;
	}
}
