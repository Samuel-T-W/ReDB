// package storage;

// import java.nio.ByteBuffer;

// /**
// * Leaf index page for a B+ tree where one page equals one node.
// *
// * <p>
// * Byte layout:
// *
// * <pre>
// * [nextLeafPid | {@literal <k, rid>} | {@literal <k, rid>} | ... | {@literal
// <k, rid>} | free
// space]
// * </pre>
// *
// * <p>
// * Each item is {@code keySize + 8} bytes (key + pageId + slotId).
// */
// public class LeafIndexPage implements IndexPage {

// private static final int NEXT_LEAF_OFFSET = 0;
// private static final int NUM_ITEMS_OFFSET = 4;
// private static final int HEADER_SIZE = 8; // nextLeafPid (4B) + numItems (4B)
// private static final int RID_SIZE = 8; // pageId (4B) + slotId (4B)

// private final RawPage rawPage;
// private final int keySize;
// private final int itemSize; // keySize + RID_SIZE

// /**
// * Wraps an existing RawPage (e.g. from the BufferManager) as a
// * LeafIndexPage.
// *
// * @param rawPage the backing raw page
// * @param keySize size of each key in bytes
// */
// public LeafIndexPage(RawPage rawPage, int keySize) {
// this.rawPage = rawPage;
// this.keySize = keySize;
// this.itemSize = keySize + RID_SIZE;
// }

// private ByteBuffer buffer() {
// return ByteBuffer.wrap(rawPage.getByteArray());
// }

// // ---- header accessors ----

// /**
// * Returns the page ID of the next adjacent leaf node in the linked list.
// *
// * @return the pageId of the next leaf node, or -1 if there is no next leaf
// */
// public int getNextLeafPid() {
// return buffer().getInt(NEXT_LEAF_OFFSET);
// }

// /**
// * Sets the page ID of the next adjacent leaf node.
// *
// * @param pageId the pageId of the next leaf, or -1 for none
// */
// public void setNextLeafPid(int pageId) {
// buffer().putInt(NEXT_LEAF_OFFSET, pageId);
// }

// /**
// * Returns the number of key/rid items currently stored in this leaf.
// */
// private int getNumItems() {
// return buffer().getInt(NUM_ITEMS_OFFSET);
// }

// /**
// * Writes the number of key/rid items stored in this leaf to the header.
// *
// * @param n the new item count
// */
// private void writeNumItems(int n) {
// buffer().putInt(NUM_ITEMS_OFFSET, n);
// }

// // ---- IndexPage interface ----

// @Override
// public boolean isLeafNode() {
// return true;
// }

// @Override
// public int getCapacity() {
// return (RawPage.MAX_PAGE_LEN - HEADER_SIZE) / itemSize;
// }

// @Override
// public int getSize() {
// return getNumItems();
// }

// @Override
// public boolean isFull() {
// return getNumItems() >= getCapacity();
// }

// // ---- Leaf-specific methods ----

// /**
// * Returns the byte offset of the item at the given index within the page
// data.
// */
// private int itemOffset(int itemIndex) {
// return HEADER_SIZE + itemIndex * itemSize;
// }

// /**
// * Returns the serialized key bytes for the item at the given index.
// *
// * @param itemIndex the zero-based index of the item within this leaf
// * @return the key as a byte array
// * @throws IndexOutOfBoundsException if itemIndex is out of [0, size)
// */
// public byte[] getItemKey(int itemIndex) {
// if (itemIndex < 0 || itemIndex >= getNumItems()) {
// throw new IndexOutOfBoundsException(
// "itemIndex " + itemIndex + " out of range (size=" + getNumItems() + ")");
// }
// int offset = itemOffset(itemIndex);
// byte[] key = new byte[keySize];
// buffer().get(offset, key);
// return key;
// }

// /**
// * Returns the record ID (pageId, slotId) associated with the item at the
// * given index.
// *
// * @param itemIndex the zero-based index of the item within this leaf
// * @return the {@link RecordId} of the data record
// * @throws IndexOutOfBoundsException if itemIndex is out of [0, size)
// */
// public RecordId getItemRid(int itemIndex) {
// if (itemIndex < 0 || itemIndex >= getNumItems()) {
// throw new IndexOutOfBoundsException(
// "itemIndex " + itemIndex + " out of range (size=" + getNumItems() + ")");
// }
// int offset = itemOffset(itemIndex) + keySize;
// int pageId = buffer().getInt(offset);
// int slotId = buffer().getInt(offset + 4);
// return new RecordId(pageId, slotId);
// }

// /**
// * Inserts the item (key + record ID) at the given index, shifting all
// * subsequent items one slot to the right.
// *
// * @param itemIndex the zero-based index at which to insert
// * @param key the serialized key bytes
// * @param rid the {@link RecordId} pointing to the data record
// * @throws IllegalStateException if the page is full
// * @throws IndexOutOfBoundsException if itemIndex is out of [0, size]
// */
// public void insert(int itemIndex, byte[] key, RecordId rid) {
// if (isFull()) {
// throw new IllegalStateException("Page is full");
// }
// int numItems = getNumItems();
// if (itemIndex < 0 || itemIndex > numItems) {
// throw new IndexOutOfBoundsException("itemIndex " + itemIndex + " out of range
// (size="
// + numItems + ")");
// }
// byte[] data = rawPage.getByteArray();
// System.arraycopy(data, itemOffset(itemIndex), data, itemOffset(itemIndex +
// 1),
// (numItems - itemIndex) * itemSize);
// int offset = itemOffset(itemIndex);
// buffer().put(offset, key);
// buffer().putInt(offset + keySize, rid.pageId());
// buffer().putInt(offset + keySize + 4, rid.slotId());
// writeNumItems(numItems + 1);
// }

// // deletions not required for this lab
// // /**
// // * Removes the item at the given index, shifting all subsequent items one
// // * slot to the left.
// // *
// // * @param itemIndex the zero-based index of the item to remove
// // * @throws IndexOutOfBoundsException if itemIndex is out of [0, size)
// // */
// // public void remove(int itemIndex) {
// // int numItems = getNumItems();
// // if (itemIndex < 0 || itemIndex >= numItems) {
// // throw new IndexOutOfBoundsException("itemIndex " + itemIndex + " out of
// range
// // (size=" + numItems + ")");
// // }
// // byte[] data = rawPage.getByteArray();
// // System.arraycopy(data, itemOffset(itemIndex + 1), data,
// // itemOffset(itemIndex),
// // (numItems - itemIndex - 1) * itemSize);
// // writeNumItems(numItems - 1);
// // }
// }
