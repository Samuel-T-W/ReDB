package storage;

import java.nio.ByteBuffer;
import java.util.Arrays;
import storage.InternalPage.SplitResult;

/**
 * A fixed-size page representing a <b>leaf node</b> in a B+-Tree.
 *
 * <h2>Page Layout (byte array)</h2>
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │                          HEADER                                  │
 * |  isLeaf      : 4 bytes  (int)  (0 = Non-Leaf, 1 = Leaf)          │
 * │  pageId      : 4 bytes  (int)                                    │
 * │  parentId    : 4 bytes  (int)  (-1 = root / no parent)           │
 * │  prevPageId  : 4 bytes  (int)  (-1 = no prev leaf)               │
 * │  nextPageId  : 4 bytes  (int)  (-1 = no next leaf)               │
 * │  degree      : 4 bytes  (int)  (max slots = degree - 1)          │
 * │  size        : 4 bytes  (int)  (current number of key-Rid slots) │
 * ├──────────────────────────────────────────────────────────────────┤
 * │                         SLOT ARRAY                               │
 * │  slot[i] = [ key (KEY_SIZE bytes) | rid (RID_SIZE bytes) ]       │
 * │  slot[0] … slot[degree-2]                                        │
 * └──────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>
 * Each slot stores exactly one (key, Rid) pair. Duplicate keys are allowed and
 * each duplicate occupies its own slot, kept in sorted key order.
 *
 * <p>
 * Keys and Rids are stored as raw bytes via pluggable {@link KeySerializer} and
 * {@link RidSerializer} interfaces, keeping the page class type-agnostic.
 *
 * <p>
 * Leaf pages are chained into a singly-linked list via {@code nextPageId} to
 * support efficient sequential and range scans.
 *
 * @param <K>
 *            Key type
 * @param <Rid>
 *            Record-ID type stored alongside each key.
 */
public class LeafPage implements IndexPage {
	// -----------------------------------------------------------------------
	// Header layout constants
	// -----------------------------------------------------------------------

	private static final int PAGE_SIZE = 4096;
	private final int KEY_SIZE;
	private static final int RID_SIZE = 8;
	private static final int OFFSET_IS_LEAF = 0; // 4 bytes
	private static final int OFFSET_PAGE_ID = 4; // 4 bytes
	private static final int OFFSET_PARENT_ID = 8; // 4 bytes
	private static final int OFFSET_PREV_PAGE = 12; // 4 bytes
	private static final int OFFSET_NEXT_PAGE = 16; // 4 bytes
	private static final int OFFSET_DEGREE = 20; // 4 bytes
	private static final int OFFSET_SIZE = 24; // 4 bytes
	private static final int HEADER_SIZE = 28; // bytes total

	/** Sentinel: no parent (this leaf is also the root). */
	public static final int NO_PARENT = -1;

	/** Sentinel: no previous leaf (this is the first leaf in the chain). */
	public static final int NO_PREV = -1;

	/** Sentinel: no next leaf (this is the last leaf in the chain). */
	public static final int NO_NEXT = -1;

	// -----------------------------------------------------------------------
	// Fields
	// -----------------------------------------------------------------------

	/** The raw page bytes – single source of truth. */
	private byte[] data;

	/** Byte size of one slot: keySize + ridSize. */
	private int slotSize;

	/** Byte offset where slot 0 begins. */
	private int slotsOffset;

	// -----------------------------------------------------------------------
	// Constructors
	// -----------------------------------------------------------------------

	/**
	 * Allocates a brand-new, empty leaf page.
	 *
	 * @param pageId
	 *            Unique identifier for this page.
	 * @param parentId
	 *            Page-ID of parent ({@link #NO_PARENT} if root).
	 * @param nextPageId
	 *            Page-ID of the next leaf ({@link #NO_NEXT} if last).
	 * @param degree
	 *            Maximum number of children (max slots = degree − 1).
	 */
	public LeafPage(int pageId, int parentId, int prevPageId, int nextPageId, int degree, int keySize) {
		if (degree < 2)
			throw new IllegalArgumentException("Degree must be >= 2, got: " + degree);
		KEY_SIZE = keySize;
		this.slotSize = KEY_SIZE + RID_SIZE;
		this.slotsOffset = HEADER_SIZE;

		// One extra slots for ease of inserting and splitting
		int pageSize = HEADER_SIZE + (degree) * slotSize;
		if (pageSize > PAGE_SIZE)
			throw new IllegalArgumentException("Page Size is too large, Choose a smaller order/degree value!");

		this.data = new byte[pageSize];

		ByteBuffer buf = wrap();
		buf.putInt(OFFSET_PAGE_ID, pageId);
		buf.putInt(OFFSET_PARENT_ID, parentId);
		buf.putInt(OFFSET_PREV_PAGE, prevPageId);
		buf.putInt(OFFSET_NEXT_PAGE, nextPageId);
		buf.putInt(OFFSET_DEGREE, degree);
		buf.putInt(OFFSET_SIZE, 0);
		buf.putInt(OFFSET_IS_LEAF, 1);
	}

	/**
	 * Wraps an existing byte array as a leaf page (e.g. after reading from disk).
	 *
	 * @param data
	 *            Raw page bytes (not copied – mutations affect the array).
	 */
	public LeafPage(byte[] data, int keySize) {
		if (data == null || data.length < HEADER_SIZE)
			throw new IllegalArgumentException("data is null or too short");

		KEY_SIZE = keySize;
		this.data = data;
		this.slotSize = KEY_SIZE + RID_SIZE;
		this.slotsOffset = HEADER_SIZE;

		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(data);
		int degree = buf.getInt(OFFSET_DEGREE);
		if (degree < 2)
			throw new IllegalArgumentException("Degree must be >= 2, got: " + degree);

		// One extra slots for ease of inserting and splitting
		int pageSize = HEADER_SIZE + (degree) * slotSize;
		if (pageSize > PAGE_SIZE)
			throw new IllegalArgumentException("Page Size is too large, Choose a smaller order/degree value!");
	}

	// -----------------------------------------------------------------------
	// Header accessors
	// -----------------------------------------------------------------------

	/** Returns this page's unique identifier. */
	public int getPageId() {
		return wrap().getInt(OFFSET_PAGE_ID);
	}

	/** Returns the parent page-ID, or {@link #NO_PARENT} if this is the root. */
	public int getParentId() {
		return wrap().getInt(OFFSET_PARENT_ID);
	}

	/** Sets the parent page-ID. */
	public void setParentId(int parentId) {
		wrap().putInt(OFFSET_PARENT_ID, parentId);
	}

	/**
	 * Returns the previous leaf's page-ID, or {@link #NO_PREV} if this is the last
	 * leaf.
	 */
	public int getPrevPageId() {
		return wrap().getInt(OFFSET_PREV_PAGE);
	}

	/**
	 * Returns the next leaf's page-ID, or {@link #NO_NEXT} if this is the last
	 * leaf.
	 */
	public int getNextPageId() {
		return wrap().getInt(OFFSET_NEXT_PAGE);
	}

	/** Sets the next leaf's page-ID. */
	public void setNextPageId(int nextPageId) {
		wrap().putInt(OFFSET_NEXT_PAGE, nextPageId);
	}

	/**
	 * Returns the degree of this page (maximum number of children/pointers).
	 * Maximum number of slots = degree − 1.
	 */
	public int getDegree() {
		return wrap().getInt(OFFSET_DEGREE);
	}

	/** Returns the current number of (key, Rid) slots in use. */
	public int getSize() {
		return wrap().getInt(OFFSET_SIZE);
	}

	/** Returns {@code true} when the page holds more slots than allowed. */
	public boolean isOverflow() {
		return getSize() >= getDegree();
	}

	/** Returns the raw page bytes. */
	public byte[] getData() {
		return this.data;
	}

	/** Returns true when this page is the root (no parent). */
	public boolean isRoot() {
		return getParentId() == NO_PARENT;
	}

	// -----------------------------------------------------------------------
	// Slot access
	// -----------------------------------------------------------------------

	/**
	 * Returns the separator key at position {@code index} (0-based).
	 *
	 * @throws IndexOutOfBoundsException
	 *             if index is out of range.
	 */
	public K getKey(int index) {
		checkSlotIndex(index);
		int off = keyOffsetAt(index);
		byte[] raw = Arrays.copyOfRange(data, off, off + KEY_SIZE);
		return new K(raw);
	}

	/**
	 * Returns the Rid stored in slot {@code index} (0-based).
	 *
	 * @throws IndexOutOfBoundsException
	 *             if index is out of range.
	 */
	public RecordId getRid(int index) {
		checkSlotIndex(index);
		int off = ridOffsetAt(index);
		byte[] rid = Arrays.copyOfRange(data, off, off + RID_SIZE);
		ByteBuffer buf = ByteBuffer.wrap(rid);
		int pageID = buf.getInt(0);
		int slotID = buf.getInt(4);
		return new RecordId(pageID, slotID);
	}

	// -----------------------------------------------------------------------
	// Insert
	// -----------------------------------------------------------------------

	/**
	 * Inserts a (key, Rid) pair into this page, maintaining sorted key order.
	 * Duplicate keys are placed after all existing slots with the same key.
	 *
	 * <p>
	 * Call {@link #isOverflow()} after insertion to decide whether to split.
	 *
	 * @throws IllegalStateException
	 *             if the page is physically full.
	 */
	public void insert(K key, RecordId rid) {
		int size = getSize();
		int degree = getDegree();
		if (size >= degree)
			throw new IllegalStateException("Page " + getPageId() + " is full (size=" + size + ", degree=" + degree
					+ "), Needs to be splitted");

		// Find insertion position: after all existing slots with the same key.
		int pos = keySearch(key, rid);

		// Shift slots [pos..size-1] one position to the right.
		shiftSlotsRight(pos, size);

		// Write the new slot.
		writeKey(pos, key);
		writeRid(pos, rid);
		incrementSize();
	}

	// -----------------------------------------------------------------------
	// key search
	// -----------------------------------------------------------------------

	/**
	 * Searches for {@code key} among the separator keys using linear search.
	 *
	 * @return index of the key if found (≥ 0)
	 */
	public int keySearch(K key, RecordId rid) {
		// Convert key to byte format
		byte[] searchKey = key.getKeyAsBytes();
		int pageId = rid.pageId();
		int slotId = rid.slotId();
		byte[] ridBytes = ByteBuffer.allocate(8).putInt(pageId).putInt(slotId).array();

		byte[] searchEntry = ByteBuffer.allocate(searchKey.length + ridBytes.length).put(searchKey).put(ridBytes)
				.array();
		for (int i = 0; i < this.getSize(); i++) {
			// Byte array of entry
			checkSlotIndex(i);
			int off = slotOffset(i);
			byte[] curEntry = Arrays.copyOfRange(data, off, off + KEY_SIZE + RID_SIZE);

			int cmp = Arrays.compare(curEntry, searchEntry);
			if (cmp >= 0)
				return i;
		}
		return this.getSize();
	}

	// -----------------------------------------------------------------------
	// Split
	// -----------------------------------------------------------------------

	/**
	 * Splits this (over-full) leaf page in half and returns the copy-up key
	 * together with the new right sibling.
	 *
	 * <p>
	 * After the call:
	 *
	 * <ul>
	 * <li>This page retains slots [0 .. mid-1].
	 * <li>The right sibling contains slots [mid .. size-1].
	 * <li>The sibling's {@code nextPageId} is set to this page's old next; this
	 * page's {@code
	 *       nextPageId} is updated to point to the sibling.
	 * <li>{@link SplitResult#copyUpKey} is the sibling's first (smallest) key.
	 * </ul>
	 *
	 * @param newPageId
	 *            Page-ID to assign to the new right sibling.
	 * @return A {@link SplitResult} with the copy-up key and the sibling page.
	 * @throws IllegalStateException
	 *             if this page is not in overflow.
	 */
	public SplitResult split(int newPageId) {
		if (!isOverflow())
			throw new IllegalStateException("Page " + getPageId() + " is not in overflow.");

		int size = getSize();
		int mid = size / 2; // Index of key that got promoted

		// Create the right sibling.
		LeafPage sibling = new LeafPage(newPageId, getParentId(), getPageId(), getNextPageId(), getDegree(), KEY_SIZE);

		// Copy slots [mid .. size-1] into the sibling.
		for (int i = mid; i < size; i++) {
			sibling.writeKey(i - mid, getKey(i));
			sibling.writeRid(i - mid, getRid(i));
		}
		sibling.wrap().putInt(OFFSET_SIZE, size - mid);

		// Truncate this page to slots [0 .. mid-1].
		this.clearSlots(mid, size);
		this.wrap().putInt(OFFSET_SIZE, mid);

		// Re-wire the singly-linked list: this → sibling → old next.
		this.setNextPageId(newPageId);

		K copyUpKey = sibling.getKey(0);
		return new SplitResult(copyUpKey, sibling);
	}

	// -----------------------------------------------------------------------
	// Split result
	// -----------------------------------------------------------------------

	/**
	 * Returned by {@link #split(int)}. Contains the copy-up separator key (smallest
	 * key of the right sibling) and the new right sibling page.
	 */
	public static class SplitResult {
		/** Smallest key in the right sibling – to be copied up to the parent. */
		public final K copyUpKey;

		/** The newly created right sibling leaf page. */
		public final LeafPage rightSibling;

		SplitResult(K copyUpKey, LeafPage rightSibling) {
			this.copyUpKey = copyUpKey;
			this.rightSibling = rightSibling;
		}
	}

	// -----------------------------------------------------------------------
	// Private helpers
	// -----------------------------------------------------------------------

	private ByteBuffer wrap() {
		return ByteBuffer.wrap(data);
	}

	private int slotOffset(int index) {
		return slotsOffset + index * slotSize;
	}

	private int keyOffsetAt(int index) {
		return slotOffset(index);
	}

	private int ridOffsetAt(int index) {
		return slotOffset(index) + KEY_SIZE;
	}

	private void writeKey(int index, K key) {
		byte[] raw = key.getKeyAsBytes();
		System.arraycopy(raw, 0, data, keyOffsetAt(index), KEY_SIZE);
	}

	private void writeRid(int index, RecordId rid) {
		// byte[] raw = rid.toString().getBytes();
		// System.arraycopy(raw, 0, data, ridOffsetAt(index), RID_SIZE);

		ByteBuffer buf = ByteBuffer.wrap(data, ridOffsetAt(index), RID_SIZE);
		buf.putInt(rid.pageId());
		buf.putInt(rid.slotId());
	}

	private void shiftSlotsRight(int from, int end) {
		for (int i = end - 1; i >= from; i--) {
			System.arraycopy(data, slotOffset(i), data, slotOffset(i + 1), slotSize);
		}
	}

	private void clearSlots(int from, int to) {
		for (int i = from; i < to; i++) {
			Arrays.fill(data, slotOffset(i), slotOffset(i) + slotSize, (byte) 0);
		}
	}

	private void incrementSize() {
		wrap().putInt(OFFSET_SIZE, getSize() + 1);
	}

	private void checkSlotIndex(int index) {
		int size = getSize();
		if (index < 0 || index >= size)
			throw new IndexOutOfBoundsException("Slot index " + index + " out of range [0, " + size + ")");
	}

	// ---- IndexPage interface ----
	// MAY NOT ACTUALLY BE USED

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code false}, since this is an internal (non-leaf) node
	 */
	@Override
	public boolean isLeafNode() {
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		if (buf.getInt(OFFSET_IS_LEAF) == 0) {
			return false;
		} else {
			return true;
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * Capacity is the maximum number of <em>keys</em> this page can hold. Because
	 * the layout interleaves pointers and keys, the usable space is:
	 * {@code (PAGE_SIZE - HEADER_SIZE - (KEY_SIZE
	 * + 4)) / (pointerSize + keySize)} minus one slot reserved for the extra
	 * trailing pointer.
	 *
	 * @return the maximum number of keys that fit in this page
	 */
	@Override
	public int getCapacity() {
		return (int) ((PAGE_SIZE - HEADER_SIZE - (KEY_SIZE + 4)) / (KEY_SIZE + 4));
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code true} if the number of keys equals {@link #getCapacity()}
	 */
	@Override
	public boolean isFull() {
		return getSize() >= getDegree();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return PageID
	 */
	@Override
	public int getPid() {
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		return buf.getInt(OFFSET_PAGE_ID);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return raw byte array
	 */
	@Override
	public byte[] getByteArray() {
		return this.data;
	}

	/** {@inheritDoc} Fill data with new byte array */
	@Override
	public void fillPageData(byte[] raw_data) {
		if (raw_data == null || raw_data.length < HEADER_SIZE)
			throw new IllegalArgumentException("data is null or too short");
		this.data = raw_data;
		this.slotSize = KEY_SIZE + RID_SIZE;
		this.slotsOffset = HEADER_SIZE;

		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(data);
		int degree = buf.getInt(OFFSET_DEGREE);
		if (degree < 2)
			throw new IllegalArgumentException("Degree must be >= 2, got: " + degree);

		// One extra slots for ease of inserting and splitting
		int pageSize = HEADER_SIZE + (degree) * slotSize;
		if (pageSize > PAGE_SIZE)
			throw new IllegalArgumentException("Page Size is too large, CHoose a smaller order/degree value!");
	}
}
