package storage;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.Arrays;

/**
 * A fixed-size page representing an <b>internal (non-leaf) node</b> in a
 * B+-Tree.
 *
 * <h2>Page Layout (byte array)</h2>
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                        HEADER                               |
 * |  isLeaf      : 4 bytes  (int)  (0 = Non-Leaf, 1 = Leaf)     │
 * │  pageId      : 4 bytes  (int)                               │
 * │  parentId    : 4 bytes  (int)  (-1 = root / no parent)      │
 * │  degree      : 4 bytes  (int)  (max children = degree)      │
 * │  size        : 4 bytes  (int)  (current number of keys)     │
 * ├─────────────────────────────────────────────────────────────┤
 * │                      KEY SLOTS                              │
 * │  key[0]  … key[degree-2]   : degree-1 slots × KEY_SIZE      │
 * ├─────────────────────────────────────────────────────────────┤
 * │                     CHILD SLOTS                             │
 * │  child[0] … child[degree-1]: degree slots × 4 bytes (int)   │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>
 * Keys are stored as raw bytes. This keeps the page itself storage-agnostic
 * while still letting the byte array reflect the real on-disk layout.
 *
 * <p>
 * Child pointers are stored as {@code int} page-IDs (4 bytes each). There are
 * always exactly {@code size + 1} valid child pointers for {@code size}
 * separator keys.
 */
public class InternalPage implements IndexPage {
	// -----------------------------------------------------------------------
	// Header layout constants
	// -----------------------------------------------------------------------

	private static final int PAGE_SIZE = 4096;
	private final int KEY_SIZE;
	private static final int OFFSET_IS_LEAF = 0; // 4 bytes
	private static final int OFFSET_PAGE_ID = 4; // 4 bytes
	private static final int OFFSET_PARENT_ID = 8; // 4 bytes
	private static final int OFFSET_DEGREE = 12; // 4 bytes
	private static final int OFFSET_SIZE = 16; // 4 bytes
	private static final int HEADER_SIZE = 20; // bytes

	/** Sentinel value meaning "no parent" (root node). */
	public static final int NO_PARENT = -1;

	// -----------------------------------------------------------------------
	// Fields
	// -----------------------------------------------------------------------

	/** The raw page bytes – the single source of truth for all stored data. */
	private byte[] data;

	/** Byte offset in {@link #data} where key slot 0 begins. */
	private int keysOffset;

	/** Byte offset in {@link #data} where child slot 0 begins. */
	private int childrenOffset;

	/**
	 * Allocates a new, empty internal page.
	 *
	 * @param pageId
	 *            Unique identifier of this page.
	 * @param parentId
	 *            Page-ID of this page's parent ({@link #NO_PARENT} if root).
	 * @param degree
	 *            Maximum number of <em>children</em> (= max keys + 1). Must be ≥ 2.
	 */
	public InternalPage(int pageId, int parentId, int degree, int keySize) {
		if (degree < 2)
			throw new IllegalArgumentException("Degree must be >= 2, got: " + degree);

		this.keysOffset = HEADER_SIZE;
		KEY_SIZE = keySize;
		// One extra slot for ease of inserting and split
		this.childrenOffset = HEADER_SIZE + (degree) * KEY_SIZE;

		// One extra slot for ease of inserting and split
		int pageSize = childrenOffset + (degree + 1) * Integer.BYTES;
		if (pageSize > PAGE_SIZE - Integer.BYTES)
			throw new IllegalArgumentException("Page Size is too large, Choose a smaller order/degree value!");
		this.data = new byte[pageSize];

		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(data);
		buf.putInt(OFFSET_PAGE_ID, pageId);
		buf.putInt(OFFSET_PARENT_ID, parentId);
		buf.putInt(OFFSET_DEGREE, degree);
		buf.putInt(OFFSET_SIZE, 0);
		buf.putInt(OFFSET_IS_LEAF, 0);
	}

	/**
	 * Wraps an existing byte array as an internal page (e.g. after reading from
	 * disk).
	 *
	 * @param data
	 *            Raw page bytes (not copied – mutations affect the array).
	 */
	public InternalPage(byte[] data, int keySize) {
		this.data = data;
		KEY_SIZE = keySize;
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(data);

		int degree = buf.getInt(OFFSET_DEGREE);
		if (degree < 2)
			throw new IllegalArgumentException("Degree must be >= 2, got: " + degree);

		this.keysOffset = HEADER_SIZE;
		// One extra slot for ease of inserting and split
		this.childrenOffset = HEADER_SIZE + (degree) * KEY_SIZE;

		// One extra slot for ease of inserting and split
		int pageSize = childrenOffset + (degree + 1) * Integer.BYTES;
		if (pageSize > PAGE_SIZE - Integer.BYTES)
			throw new IllegalArgumentException("Page Size is too large, Choose a smaller order/degree value!");
	}

	// -----------------------------------------------------------------------
	// Header accessors
	// -----------------------------------------------------------------------

	/** Returns this page's unique identifier. */
	public int getPageId() {
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		return buf.getInt(OFFSET_PAGE_ID);
	}

	/** Returns the parent page's ID, or {@link #NO_PARENT} if this is the root. */
	public int getParentId() {
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		return buf.getInt(OFFSET_PARENT_ID);
	}

	/** Sets the parent page ID. */
	public void setParentId(int parentId) {
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		buf.putInt(OFFSET_PARENT_ID, parentId);
	}

	/**
	 * Returns the degree of this page (maximum number of children). Maximum number
	 * of keys = degree − 1.
	 */
	public int getDegree() {
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		return buf.getInt(OFFSET_DEGREE);
	}

	/** Returns the current number of separator keys stored in this page. */
	@Override
	public int getSize() {
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		return buf.getInt(OFFSET_SIZE);
	}

	/** Returns true when the page holds more keys than allowed (needs a split). */
	public boolean isOverflow() {
		return getSize() >= getDegree();
	}

	/** Returns true when this page is the root (no parent). */
	public boolean isRoot() {
		return getParentId() == NO_PARENT;
	}

	/** Returns the raw page bytes (direct reference, not a copy). */
	public byte[] getData() {
		return this.data;
	}

	// -----------------------------------------------------------------------
	// Key access
	// -----------------------------------------------------------------------

	/**
	 * Returns the separator key at position {@code index} (0-based).
	 *
	 * @throws IndexOutOfBoundsException
	 *             if index is out of range.
	 */
	public K getKey(int index) {
		checkKeyIndex(index);
		int off = keyOffset(index);
		byte[] raw = Arrays.copyOfRange(data, off, off + KEY_SIZE);
		return new K(raw);
	}

	/**
	 * Overwrites the separator key at position {@code index}.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if index is out of range.
	 */
	public void setKey(int index, K key) {
		checkKeyIndex(index);
		writeKey(index, key);
	}

	// -----------------------------------------------------------------------
	// Child access
	// -----------------------------------------------------------------------

	/**
	 * Returns the child page-ID at position {@code index} (0-based). Valid
	 * positions: 0 … size (inclusive).
	 *
	 * @throws IndexOutOfBoundsException
	 *             if index is out of range.
	 */
	public int getChildId(int index) {
		checkChildIndex(index);
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		return buf.getInt(childOffset(index));
	}

	/**
	 * Overwrites the child page-ID at position {@code index}.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if index is out of range.
	 */
	public void setChildId(int index, int pageId) {
		checkChildIndex(index);
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		buf.putInt(childOffset(index), pageId);
	}

	// -----------------------------------------------------------------------
	// Insert
	// -----------------------------------------------------------------------

	/**
	 * Inserts a separator key and its right child pointer into this page,
	 * maintaining sorted key order.
	 *
	 * <p>
	 * Call {@link #isOverflow()} after insertion to decide whether to split.
	 *
	 * @param key
	 *            The separator key to insert.
	 * @param rightChildId
	 *            Page-ID of the child immediately to the right of {@code key}.
	 * @throws IllegalStateException
	 *             if the page is already at maximum physical capacity.
	 */
	public void insertKeyAndChild(K key, int rightChildId) {
		int size = getSize();
		int degree = getDegree();
		if (size >= degree) {
			throw new IllegalStateException("Page " + getPageId() + " is overflow (size=" + size + ", degree=" + degree
					+ "), Needs to be splitted");
		}

		// Find insertion position via binary search.
		int pos = keySearch(key);

		// Shift keys and children to the right to make room.
		shiftKeysRight(pos, size);
		shiftChildrenRight(pos + 1, size + 1);

		writeKey(pos, key);

		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		buf.putInt(childOffset(pos + 1), rightChildId);
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
	public int keySearch(K key) {
		// Convert key to byte format
		byte[] searchKey = key.getKeyAsBytes();
		for (int i = 0; i < this.getSize(); i++) {
			// Byte array of key
			byte[] curKey = getKey(i).getKeyAsBytes();

			int cmp = Arrays.compare(curKey, searchKey);
			if (cmp > 0)
				return i;
		}
		return this.getSize();
	}

	/**
	 * Returns the index of the child pointer that should be followed for
	 * {@code key}.
	 *
	 * <ul>
	 * <li>If {@code key} equals separator[i], follow child[i+1] (right subtree).
	 * <li>Otherwise follow the child to the left of the first separator &gt;
	 * {@code key}.
	 * </ul>
	 */
	public int findChildIndex(K key) {
		int idx = keySearch(key);
		return idx;
	}

	// -----------------------------------------------------------------------
	// Split
	// -----------------------------------------------------------------------

	/**
	 * Splits this (over-full) page into two pages of roughly equal size and returns
	 * the pushed-up separator key together with the new right sibling.
	 *
	 * <p>
	 * After the call:
	 *
	 * <ul>
	 * <li>This page retains the left half of the keys/children.
	 * <li>The returned {@link SplitResult#rightSibling} holds the right half.
	 * <li>The middle key is <em>pushed up</em> to the parent (not kept in either
	 * child).
	 * <li>The sibling's {@code parentId} is set to this page's {@code parentId};
	 * the caller should update it once the sibling is assigned its final page-ID.
	 * </ul>
	 *
	 * @param newPageId
	 *            Page-ID to assign to the newly created right sibling.
	 * @return A {@link SplitResult} containing the pushed-up key and the sibling
	 *         page.
	 * @throws IllegalStateException
	 *             if this page is not in overflow.
	 */
	public SplitResult split(int newPageId) {
		if (!isOverflow())
			throw new IllegalStateException("Page " + getPageId() + " is not in overflow.");

		int size = getSize();
		int degree = getDegree();
		int mid = size / 2; // index of the key that gets pushed up

		K pushedUpKey = getKey(mid);

		// Create the right sibling with the same degree and parent.
		InternalPage sibling = new InternalPage(newPageId, getParentId(), degree, KEY_SIZE);

		// Copy keys [mid+1 .. size-1] and children [mid+1 .. size] to sibling.
		int siblingSize = 0;
		for (int i = mid + 1; i < size; i++) {
			sibling.writeKey(siblingSize, getKey(i));
			sibling.wrap().putInt(sibling.childOffset(siblingSize), getChildId(i));
			siblingSize++;
		}
		// Copy the last child pointer.
		sibling.wrap().putInt(sibling.childOffset(siblingSize), this.getChildId(size));
		sibling.wrap().putInt(OFFSET_SIZE, siblingSize);

		// Truncate this page to keys [0 .. mid-1] and children [0 .. mid].
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		buf.putInt(OFFSET_SIZE, mid);
		// Zero out vacated slots for cleanliness (optional but good practice).
		clearSlots(mid, size);

		return new SplitResult(pushedUpKey, sibling);
	}

	// -----------------------------------------------------------------------
	// Split result
	// -----------------------------------------------------------------------

	/**
	 * Returned by {@link #split()}. Contains the pushed-up separator key and the
	 * newly created right sibling page.
	 */
	public static class SplitResult {
		/** The key to be inserted into the parent node. */
		public final K pushedUpKey;

		/** The new right sibling page (already populated). */
		public final InternalPage rightSibling;

		SplitResult(K pushedUpKey, InternalPage rightSibling) {
			this.pushedUpKey = pushedUpKey;
			this.rightSibling = rightSibling;
		}
	}

	// -----------------------------------------------------------------------
	// Private helpers
	// -----------------------------------------------------------------------

	public ByteBuffer wrap() {
		return ByteBuffer.wrap(this.data);
	}

	/** Byte offset of key slot {@code index}. */
	private int keyOffset(int index) {
		return keysOffset + index * KEY_SIZE;
	}

	/** Byte offset of child slot {@code index}. */
	private int childOffset(int index) {
		return childrenOffset + index * Integer.BYTES;
	}

	private void writeKey(int index, K key) {
		byte[] raw = key.getKeyAsBytes();
		System.arraycopy(raw, 0, data, keyOffset(index), KEY_SIZE);
	}

	private void incrementSize() {
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(this.data);
		buf.putInt(OFFSET_SIZE, getSize() + 1);
	}

	/** Shifts keys[pos..end-1] one slot to the right. */
	private void shiftKeysRight(int pos, int end) {
		for (int i = end - 1; i >= pos; i--) {
			System.arraycopy(data, keyOffset(i), data, keyOffset(i + 1), KEY_SIZE);
		}
	}

	/** Shifts children[pos..end-1] one slot to the right. */
	private void shiftChildrenRight(int pos, int end) {
		ByteBuffer buf = wrap();
		for (int i = end - 1; i >= pos; i--) {
			buf.putInt(childOffset(i + 1), buf.getInt(childOffset(i)));
		}
	}

	/** Zeroes key slots [from..to] and corresponding right child slots. */
	private void clearSlots(int from, int to) {
		ByteBuffer buf = wrap();
		for (int i = from; i < to; i++) {
			Arrays.fill(data, keyOffset(i), keyOffset(i) + KEY_SIZE, (byte) 0);
			buf.putInt(childOffset(i + 1), 0);
		}
	}

	private void checkKeyIndex(int index) {
		int size = getSize();
		if (index < 0 || index >= size)
			throw new IndexOutOfBoundsException("Key index " + index + " out of range [0, " + size + ")");
	}

	private void checkChildIndex(int index) {
		int size = getSize();
		if (index < 0 || index > size)
			throw new IndexOutOfBoundsException("Child index " + index + " out of range [0, " + (size + 1) + ")");
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
		this.data = raw_data;
		// Use ByteBuffer for ease of inserting values to byte array
		ByteBuffer buf = ByteBuffer.wrap(data);

		int degree = buf.getInt(OFFSET_DEGREE);
		if (degree < 2)
			throw new IllegalArgumentException("Degree must be >= 2, got: " + degree);

		this.keysOffset = HEADER_SIZE;
		// One extra slot for ease of inserting and split
		this.childrenOffset = HEADER_SIZE + (degree) * KEY_SIZE;

		// One extra slot for ease of inserting and split
		int pageSize = childrenOffset + (degree + 1) * Integer.BYTES;
		if (pageSize > PAGE_SIZE - Integer.BYTES)
			throw new IllegalArgumentException("Page Size is too large, Choose a smaller order/degree value!");
	}
}
