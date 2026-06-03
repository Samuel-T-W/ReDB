// package storage;

// import java.nio.ByteBuffer;

// /**
// * Non-leaf (internal) index page for a B+ tree where one page equals one
// node.
// *
// * <p>
// * Byte layout:
// *
// * <pre>
// * p -> child pointer (pageId) -> 4 bytes
// * k -> index key -> {@code keySize} bytes
// *
// * [numKeys (4B) | p0 | k0 | p1 | k1 | ... | p_{n-1} | k_{n-1} | p_n | free
// space]
// * </pre>
// *
// * <p>
// * For {@code n} keys there are {@code n+1} child pointers. The routing rule
// is:
// * <ul>
// * <li>{@code p[0]} — subtree with keys {@literal <} {@code k[0]}</li>
// * <li>{@code p[i]} (0 &lt; i &lt; n) — subtree with keys in
// * [{@code k[i-1]}, {@code k[i]})</li>
// * <li>{@code p[n]} — subtree with keys {@literal >=} {@code k[n-1]}</li>
// * </ul>
// *
// * <p>
// * State variables:
// * <ul>
// * <li>{@code rawPage} - the backing {@link RawPage} that owns the 4096-byte
// * array</li>
// * <li>{@code keySize} - size of each key in bytes, fixed at construction
// * time</li>
// * <li>{@code pointerSize} - size of each child pointer in bytes (pageId = 4
// * bytes, constant)</li>
// * <li>{@code numKeys} - number of keys currently stored; there are always
// * {@code numKeys + 1} valid child pointers</li>
// * </ul>
// */
// public class NonLeafIndexPage implements IndexPage {

// private static final int NUM_KEYS_OFFSET = 0;
// private static final int HEADER_SIZE = 4; // numKeys (4B)
// private static final int POINTER_SIZE = 4; // pageId (4B)

// private final RawPage rawPage;
// private final int keySize;

// /**
// * Creates a new NonLeafIndexPage with one initial child pointer and zero
// keys.
// *
// * <p>
// * A non-leaf node is always born with at least one child (the left child
// before
// * any keys). This constructor writes {@code initialLeftChildPageId} as
// * {@code p[0]} and sets {@code numKeys = 0}. Every subsequent
// * {@link #insert(int, byte[], int)} adds a key and a right child pointer.
// *
// * @param rawPage the backing raw page
// * @param keySize size of each key in bytes
// * @param initialLeftChildPageId the pageId of the initial left child ({@code
// p[0]})
// */
// public NonLeafIndexPage(RawPage rawPage, int keySize, int
// initialLeftChildPageId) {
// this.rawPage = rawPage;
// this.keySize = keySize;
// writeNumKeys(0);
// buffer().putInt(HEADER_SIZE, initialLeftChildPageId);
// }

// private ByteBuffer buffer() {
// return ByteBuffer.wrap(rawPage.getByteArray());
// }

// private int getNumKeys() {
// return buffer().getInt(NUM_KEYS_OFFSET);
// }

// private void writeNumKeys(int n) {
// buffer().putInt(NUM_KEYS_OFFSET, n);
// }

// private int pointerOffset(int index) {
// return HEADER_SIZE + index * (POINTER_SIZE + keySize);
// }

// private int keyOffset(int index) {
// return HEADER_SIZE + index * (POINTER_SIZE + keySize) + POINTER_SIZE;
// }

// // ---- IndexPage interface ----

// /**
// * {@inheritDoc}
// *
// * @return {@code false}, since this is an internal (non-leaf) node
// */
// @Override
// public boolean isLeafNode() {
// return false;
// }

// /**
// * {@inheritDoc}
// *
// * <p>
// * Capacity is the maximum number of <em>keys</em> this page can hold. Because
// * the layout interleaves pointers and keys, the usable space is:
// * {@code (PAGE_SIZE - HEADER_SIZE) / (pointerSize + keySize)} minus one slot
// * reserved for the extra trailing pointer.
// *
// * @return the maximum number of keys that fit in this page
// */
// @Override
// public int getCapacity() {
// return (RawPage.MAX_PAGE_LEN - HEADER_SIZE - POINTER_SIZE) / (POINTER_SIZE +
// keySize);
// }

// /**
// * {@inheritDoc}
// *
// * @return the number of keys currently stored in this node
// */
// @Override
// public int getSize() {
// return getNumKeys();
// }

// /**
// * {@inheritDoc}
// *
// * @return {@code true} if the number of keys equals {@link #getCapacity()}
// */
// @Override
// public boolean isFull() {
// return getNumKeys() >= getCapacity();
// }

// // ---- Non-leaf-specific methods ----

// /**
// * Returns the page ID of the child node at the given pointer index.
// *
// * <p>
// * Valid pointer indices are 0 through {@code getSize()} inclusive (one more
// * than the number of keys).
// *
// * @param index the zero-based pointer index
// * @return the pageId of the child node
// * @throws IndexOutOfBoundsException if {@code index} is outside
// * [0, getSize()]
// */
// public int getPointer(int index) {
// int numKeys = getNumKeys();
// if (index < 0 || index > numKeys) {
// throw new IndexOutOfBoundsException(
// "index " + index + " out of range (numKeys=" + numKeys + ")");
// }
// return buffer().getInt(pointerOffset(index));
// }

// /**
// * Returns the serialized key bytes at the given key index.
// *
// * @param index the zero-based key index (0 to {@code getSize() - 1})
// * @return a copy of the key as a byte array of length {@code keySize}
// * @throws IndexOutOfBoundsException if {@code index} is outside
// * [0, getSize())
// */
// public byte[] getKey(int index) {
// int numKeys = getNumKeys();
// if (index < 0 || index >= numKeys) {
// throw new IndexOutOfBoundsException(
// "index " + index + " out of range (numKeys=" + numKeys + ")");
// }
// byte[] key = new byte[keySize];
// buffer().get(keyOffset(index), key);
// return key;
// }

// /**
// * Overwrites the key at the given index in place without changing the node
// * structure or pointer count.
// *
// * @param index the zero-based key index (0 to {@code getSize() - 1})
// * @param key the serialized key bytes; must have length equal to
// * {@code keySize}
// * @throws IndexOutOfBoundsException if {@code index} is outside
// * [0, getSize())
// * @throws IllegalArgumentException if {@code key.length != keySize}
// */
// public void setKey(int index, byte[] key) {
// int numKeys = getNumKeys();
// if (index < 0 || index >= numKeys) {
// throw new IndexOutOfBoundsException(
// "index " + index + " out of range (numKeys=" + numKeys + ")");
// }
// if (key.length != keySize) {
// throw new IllegalArgumentException("key.length=" + key.length + " !=
// keySize=" +
// keySize);
// }
// buffer().put(keyOffset(index), key);
// }

// /**
// * Inserts a key and its right child pointer at the given key index, shifting
// * all subsequent keys and pointers one slot to the right.
// *
// * <p>
// * After insertion the invariant {@code pointer count == key count + 1} is
// * maintained: {@code rightChildPageId} becomes {@code p[keyIndex + 1]}, and
// * the existing {@code p[keyIndex]} remains the left child of the new key.
// *
// * @param keyIndex the zero-based index at which to insert the key
// * @param key the serialized key bytes; must have length equal to
// * {@code keySize}
// * @param rightChildPageId the pageId of the new right child for this key
// * @throws IllegalStateException if the page is full
// * @throws IndexOutOfBoundsException if {@code keyIndex} is outside
// * [0, getSize()]
// * @throws IllegalArgumentException if {@code key.length != keySize}
// */
// public void insert(int keyIndex, byte[] key, int rightChildPageId) {
// if (isFull()) {
// throw new IllegalStateException("Page is full");
// }
// int numKeys = getNumKeys();
// if (keyIndex < 0 || keyIndex > numKeys) {
// throw new IndexOutOfBoundsException(
// "keyIndex " + keyIndex + " out of range (numKeys=" + numKeys + ")");
// }
// if (key.length != keySize) {
// throw new IllegalArgumentException("key.length=" + key.length + " !=
// keySize=" +
// keySize);
// }
// byte[] data = rawPage.getByteArray();
// int srcOffset = keyOffset(keyIndex);
// int bytesToShift = (numKeys - keyIndex) * (POINTER_SIZE + keySize);
// System.arraycopy(data, srcOffset, data, srcOffset + (POINTER_SIZE + keySize),
// bytesToShift);
// buffer().put(srcOffset, key);
// buffer().putInt(srcOffset + keySize, rightChildPageId);
// writeNumKeys(numKeys + 1);
// }

// // deletions not required for this lab
// // /**
// // * Removes the key at the given index and its associated right child
// pointer
// // * ({@code p[keyIndex + 1]}), shifting all subsequent keys and pointers one
// // * slot to the left.
// // *
// // * @param keyIndex the zero-based index of the key to remove
// // * @throws IndexOutOfBoundsException if {@code keyIndex} is outside
// // * [0, getSize())
// // */
// // public void remove(int keyIndex) {
// // int numKeys = getNumKeys();
// // if (keyIndex < 0 || keyIndex >= numKeys) {
// // throw new IndexOutOfBoundsException(
// // "keyIndex " + keyIndex + " out of range (numKeys=" + numKeys + ")");
// // }
// // byte[] data = rawPage.getByteArray();
// // int srcOffset = keyOffset(keyIndex);
// // int bytesToShift = (numKeys - keyIndex - 1) * (POINTER_SIZE + keySize);
// // System.arraycopy(data, srcOffset + (POINTER_SIZE + keySize), data,
// srcOffset,
// bytesToShift);
// // writeNumKeys(numKeys - 1);
// // }
// }
