package storage;

import buffer.BufferManager;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class BTreeManager implements BTree {

	// -----------------------------------------------------------------------
	// Fields
	// -----------------------------------------------------------------------

	/** Page-ID of the current root (updated when the root splits). */
	private int rootId;

	private final int degree;
	private final String fileId;
	private final BufferManager bufferManager;
	private final int KEY_SIZE;

	// -----------------------------------------------------------------------
	// Constructor
	// -----------------------------------------------------------------------

	/**
	 * Creates a brand-new B+ Tree, writing an internal root and one empty leaf
	 * child into the buffer pool.
	 *
	 * @param degree
	 *            Max children for internal pages (= max slots + 1 for leaves).
	 * @param fileId
	 *            File identifier passed to every {@link BufferManager} call.
	 * @param bufferManager
	 *            The buffer manager that owns the page cache.
	 */
	public BTreeManager(int degree, String fileId, BufferManager bufferManager, int keySize) throws IOException {
		this.degree = degree;
		this.fileId = fileId;
		this.bufferManager = bufferManager;
		KEY_SIZE = keySize;

		// --- Step 1: allocate the root internal page (pinned). ----------------
		RawPage rootRaw = allocateRawInternalPage(InternalPage.NO_PARENT);
		this.rootId = rootRaw.getPid();

		// --- Step 2: allocate the first leaf (pinned), with root as parent. ---
		RawPage leafRaw = allocateRawLeafPage(rootId, LeafPage.NO_PREV, LeafPage.NO_NEXT);
		int leafId = leafRaw.getPid();

		// --- Step 3: point root's child[0] at the new leaf. -------------------
		InternalPage root = new InternalPage(rootRaw.getByteArray(), keySize);
		root.setChildId(0, leafId);

		// --- Step 4: persist both pages and release pins. ---------------------
		bufferManager.markDirty(fileId, rootId);
		bufferManager.unpinPage(fileId, rootId);
		bufferManager.markDirty(fileId, leafId);
		bufferManager.unpinPage(fileId, leafId);
	}

	private BTreeManager(int degree, String fileId, BufferManager bufferManager, int keySize, int rootId) {
		this.degree = degree;
		this.fileId = fileId;
		this.bufferManager = bufferManager;
		KEY_SIZE = keySize;
		this.rootId = rootId;
	}

	/**
	 * Opens an already-built B+ tree index file.
	 *
	 * <p>The current on-disk format does not have a metadata page, so the root is
	 * found by scanning for the only index page whose parentId is NO_PARENT.
	 */
	public static BTreeManager openExisting(
			int degree,
			String fileId,
			BufferManager bufferManager,
			int keySize) throws IOException {
		int rootId = findRootPageId(fileId);
		return new BTreeManager(degree, fileId, bufferManager, keySize, rootId);
	}

	private static int findRootPageId(String fileId) throws IOException {
		File file = new File(fileId);
		long length = file.length();
		if (length == 0) {
			throw new IllegalStateException("Index file is empty: " + fileId + ". Run pre_process first.");
		}
		if (length % RawPage.MAX_PAGE_LEN != 0) {
			throw new IllegalStateException("Index file size is not a multiple of pages: " + fileId);
		}

		int rootId = -1;
		int pageCount = Math.toIntExact(length / RawPage.MAX_PAGE_LEN);
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			byte[] header = new byte[12];
			for (int pageId = 0; pageId < pageCount; pageId++) {
				raf.seek(RawPage.getOffset(pageId));
				raf.readFully(header);
				int parentId = ByteBuffer.wrap(header).getInt(8);
				if (parentId == InternalPage.NO_PARENT) {
					if (rootId != -1) {
						throw new IllegalStateException("Index file has multiple root pages: " + fileId);
					}
					rootId = pageId;
				}
			}
		}

		if (rootId == -1) {
			throw new IllegalStateException("Index file has no root page: " + fileId);
		}
		return rootId;
	}

	// -----------------------------------------------------------------------
	// BTree interface
	// -----------------------------------------------------------------------

	/** Get rootId */
	public int getRoot() {
		return this.rootId;
	}

	/** Inserts {@code (key, r)} into the tree, splitting pages as needed. */
	@Override
	public void insert(K key, RecordId r) {
		try {
			insertInternal(key, r);
		} catch (IOException e) {
			throw new RuntimeException("B+ tree insert failed", e);
		}
	}

	/**
	 * Returns an {@link Iterator} over every {@link RecordId} stored under
	 * {@code key}. Returns an empty iterator when the key is absent.
	 */
	@Override
	public Iterator<RecordId> search(K key) {
		try {
			return searchInternal(key);
		} catch (IOException e) {
			throw new RuntimeException("B+ tree search failed", e);
		}
	}

	/**
	 * Returns an {@link Iterator} over every {@link RecordId} whose key falls in
	 * the inclusive range {@code [startKey, endKey]}.
	 */
	@Override
	public Iterator<RecordId> rangeSearch(K startKey, K endKey) {
		try {
			return rangeSearchInternal(startKey, endKey);
		} catch (IOException e) {
			throw new RuntimeException("B+ tree rangeSearch failed", e);
		}
	}

	// -----------------------------------------------------------------------
	// Insert implementation
	// -----------------------------------------------------------------------

	private void insertInternal(K key, RecordId r) throws IOException {
		int leafId = findLeafId(key);

		// Pin the leaf, insert, and check for overflow.
		Page leafPage = bufferManager.getPage(fileId, leafId);
		LeafPage leaf = new LeafPage(leafPage.getByteArray(), KEY_SIZE);
		leaf.insert(key, r);
		bufferManager.markDirty(fileId, leafId);

		if (!leaf.isOverflow()) {
			bufferManager.unpinPage(fileId, leafId);
			return;
		}

		// --- Leaf overflow: split it. -----------------------------------------

		// Allocate a raw sibling page (pinned) so we have its assigned ID.
		RawPage sibRaw = bufferManager.createPage(fileId, null);
		int siblingId = sibRaw.getPid();

		// Split. leaf.getData() is already the buffer-pool array for leafId,
		// so the left half is updated in place (no extra copy needed).
		LeafPage.SplitResult split = leaf.split(siblingId);

		// Copy the right sibling raw bytes into the raw page.
		System.arraycopy(split.rightSibling.getData(), 0, sibRaw.getByteArray(), 0,
				split.rightSibling.getData().length);

		bufferManager.markDirty(fileId, siblingId);

		// Save the parent ID before unpinning (leaf header is still readable).
		int leafParentId = leaf.getParentId();
		bufferManager.unpinPage(fileId, leafId);
		bufferManager.unpinPage(fileId, siblingId);

		insertIntoParent(leafId, split.copyUpKey, siblingId, leafParentId);
	}

	/**
	 * Inserts the promoted key and new right sibling into the parent, creating a
	 * new root if necessary. Recursively propagates splits upward.
	 *
	 * @param leftId
	 *            Page-ID of the left child (already in the tree).
	 * @param key
	 *            Key to insert into the parent.
	 * @param rightId
	 *            Page-ID of the newly created right sibling.
	 * @param parentId
	 *            Parent page-ID, or {@link InternalPage#NO_PARENT} if the split
	 *            reached the root.
	 */
	private void insertIntoParent(int leftId, K key, int rightId, int parentId) throws IOException {

		if (parentId == InternalPage.NO_PARENT) {
			// The split has bubbled all the way up: grow the tree by one level.
			RawPage newRootRaw = allocateRawInternalPage(InternalPage.NO_PARENT);
			int newRootId = newRootRaw.getPid();

			InternalPage newRoot = new InternalPage(newRootRaw.getByteArray(), KEY_SIZE);
			newRoot.setChildId(0, leftId);
			newRoot.insertKeyAndChild(key, rightId);

			bufferManager.markDirty(fileId, newRootId);
			bufferManager.unpinPage(fileId, newRootId);

			updateParentField(leftId, newRootId);
			updateParentField(rightId, newRootId);

			this.rootId = newRootId;
			return;
		}

		// --- Insert into existing parent. ------------------------------------
		Page parentPage = bufferManager.getPage(fileId, parentId);
		InternalPage parent = new InternalPage(parentPage.getByteArray(), KEY_SIZE);
		parent.insertKeyAndChild(key, rightId);
		bufferManager.markDirty(fileId, parentId);

		// The new right child must point at this parent.
		updateParentField(rightId, parentId);

		if (!parent.isOverflow()) {
			bufferManager.unpinPage(fileId, parentId);
			return;
		}

		// --- Internal-node overflow: split the parent. -----------------------
		RawPage sibRaw = bufferManager.createPage(fileId, null);
		int siblingId = sibRaw.getPid();

		InternalPage.SplitResult split = parent.split(siblingId);

		System.arraycopy(split.rightSibling.getData(), 0, sibRaw.getByteArray(), 0,
				split.rightSibling.getData().length);

		bufferManager.markDirty(fileId, siblingId);

		// Update every child that moved into the right sibling.
		InternalPage sibling = new InternalPage(sibRaw.getByteArray(), KEY_SIZE);
		for (int i = 0; i <= sibling.getSize(); i++) {
			updateParentField(sibling.getChildId(i), siblingId);
		}

		int grandParentId = parent.getParentId();
		bufferManager.unpinPage(fileId, parentId);
		bufferManager.unpinPage(fileId, siblingId);

		insertIntoParent(parentId, split.pushedUpKey, siblingId, grandParentId);
	}

	// -----------------------------------------------------------------------
	// Search implementation
	// -----------------------------------------------------------------------

	private Iterator<RecordId> searchInternal(K key) throws IOException {
		int leafId = findLeafId(key);
		List<RecordId> results = new ArrayList<>();
		byte[] searchKeyBytes = key.getKeyAsBytes();

		while (leafId != LeafPage.NO_NEXT) {
			Page raw = bufferManager.getPage(fileId, leafId);
			LeafPage leaf = new LeafPage(raw.getByteArray(), KEY_SIZE);
			int nextId = leaf.getNextPageId();

			for (int i = 0; i < leaf.getSize(); i++) {
				int cmp = Arrays.compare(leaf.getKey(i).getKeyAsBytes(), searchKeyBytes);
				if (cmp == 0) {
					results.add(leaf.getRid(i));
				} else if (cmp > 0) {
					bufferManager.unpinPage(fileId, leafId);
					return results.iterator();
				}
			}
			bufferManager.unpinPage(fileId, leafId);

			// If no match has been found yet the key is absent; stop scanning.
			if (results.isEmpty())
				break;
			leafId = nextId;
		}
		return results.iterator();
	}

	// -----------------------------------------------------------------------
	// Range-search implementation
	// -----------------------------------------------------------------------

	private Iterator<RecordId> rangeSearchInternal(K startKey, K endKey) throws IOException {
		int leafId = findLeafId(startKey);
		List<RecordId> results = new ArrayList<>();
		byte[] startBytes = startKey.getKeyAsBytes();
		byte[] endBytes = endKey.getKeyAsBytes();

		while (leafId != LeafPage.NO_NEXT) {
			Page raw = bufferManager.getPage(fileId, leafId);
			LeafPage leaf = new LeafPage(raw.getByteArray(), KEY_SIZE);
			int nextId = leaf.getNextPageId();
			boolean pastEnd = false;

			for (int i = 0; i < leaf.getSize(); i++) {
				byte[] curBytes = leaf.getKey(i).getKeyAsBytes();
				if (Arrays.compare(curBytes, endBytes) > 0) {
					pastEnd = true;
					break;
				}
				if (Arrays.compare(curBytes, startBytes) >= 0)
					results.add(leaf.getRid(i));
			}

			bufferManager.unpinPage(fileId, leafId);
			if (pastEnd)
				break;
			leafId = nextId;
		}
		return results.iterator();
	}

	// -----------------------------------------------------------------------
	// Tree traversal
	// -----------------------------------------------------------------------

	/**
	 * Traverses internal nodes from the root to the leaf that should contain
	 * {@code key}. Each internal page is pinned only long enough to read the next
	 * child pointer.
	 *
	 * @return Page-ID of the target leaf (NOT pinned on return).
	 */
	private int findLeafId(K key) throws IOException {
		int pageId = rootId;
		while (true) {
			Page raw = bufferManager.getPage(fileId, pageId);
			byte[] data = raw.getByteArray();
			boolean isLeaf = ByteBuffer.wrap(data).getInt(0) == 1; // OFFSET_IS_LEAF
			bufferManager.unpinPage(fileId, pageId);

			if (isLeaf)
				return pageId;

			InternalPage page = new InternalPage(data, KEY_SIZE);
			pageId = page.getChildId(page.findChildIndex(key));
		}
	}

	// -----------------------------------------------------------------------
	// Bulk load (pre-sorted input)
	// -----------------------------------------------------------------------

	/**
	 * Bulk-loads a pre-sorted list of (key, rid) pairs into this tree. Faster than
	 * inserting one by one: no splits, one sequential pass.
	 *
	 * <p>
	 * Assumes keys are already in ascending order (e.g. movieId from a database
	 * loaded in order). Call on a freshly constructed BTreeManager.
	 *
	 * @param keys
	 *            already-sorted keys
	 * @param rids
	 *            corresponding record IDs (same index = same entry)
	 */
	public int bulkLoad(List<K> keys, List<RecordId> rids) throws IOException {
		if (keys.isEmpty())
			return -1;

		int numRows = 0;
		int n = keys.size();

		// --- 1. Fill leaf pages left to right ---
		int maxLeafSlots = degree - 1;
		List<Integer> pageIds = new ArrayList<>();
		List<K> separators = new ArrayList<>(); // first key of each page after the first
		int prevLeafId = LeafPage.NO_PREV;

		for (int i = 0; i < n;) {
			RawPage raw = allocateRawLeafPage(InternalPage.NO_PARENT, prevLeafId, LeafPage.NO_NEXT);
			int leafId = raw.getPid();
			LeafPage leaf = new LeafPage(raw.getByteArray(), KEY_SIZE);

			if (prevLeafId != LeafPage.NO_PREV) {
				Page prev = bufferManager.getPage(fileId, prevLeafId);
				new LeafPage(prev.getByteArray(), KEY_SIZE).setNextPageId(leafId);
				bufferManager.markDirty(fileId, prevLeafId);
				bufferManager.unpinPage(fileId, prevLeafId);
			}

			if (!pageIds.isEmpty())
				separators.add(keys.get(i));
			pageIds.add(leafId);

			for (int filled = 0; i < n && filled < maxLeafSlots; i++, filled++) {
				leaf.insert(keys.get(i), rids.get(i));
				numRows++;
			}

			bufferManager.markDirty(fileId, leafId);
			bufferManager.unpinPage(fileId, leafId);
			prevLeafId = leafId;
		}

		// --- 2. Build internal levels bottom-up until one root remains ---
		int maxInternalKeys = degree - 1;
		while (pageIds.size() > 1) {
			List<Integer> nextPageIds = new ArrayList<>();
			List<K> nextSeparators = new ArrayList<>();

			for (int j = 0; j < pageIds.size();) {
				RawPage raw = allocateRawInternalPage(InternalPage.NO_PARENT);
				int pageId = raw.getPid();
				InternalPage page = new InternalPage(raw.getByteArray(), KEY_SIZE);

				if (!nextPageIds.isEmpty())
					nextSeparators.add(separators.get(j - 1));
				nextPageIds.add(pageId);

				page.setChildId(0, pageIds.get(j));
				updateParentField(pageIds.get(j), pageId);
				j++;

				for (int filled = 0; j < pageIds.size() && filled < maxInternalKeys; j++, filled++) {
					page.insertKeyAndChild(separators.get(j - 1), pageIds.get(j));
					updateParentField(pageIds.get(j), pageId);
				}

				bufferManager.markDirty(fileId, pageId);
				bufferManager.unpinPage(fileId, pageId);
			}

			pageIds = nextPageIds;
			separators = nextSeparators;
		}

		this.rootId = pageIds.get(0);

		return numRows;
	}

	// -----------------------------------------------------------------------
	// Private helpers
	// -----------------------------------------------------------------------

	/**
	 * Updates the {@code parentId} field in the stored page to {@code newParentId}.
	 * Works for both internal and leaf pages because both layouts have parentId at
	 * byte offset 8. The page is pinned, written, marked dirty, and unpinned.
	 */
	private void updateParentField(int pageId, int newParentId) throws IOException {
		Page raw = bufferManager.getPage(fileId, pageId);
		// OFFSET_PARENT_ID = 8 in both InternalPage and LeafPage.
		ByteBuffer.wrap(raw.getByteArray()).putInt(8, newParentId);
		bufferManager.markDirty(fileId, pageId);
		bufferManager.unpinPage(fileId, pageId);
	}

	/**
	 * Allocates a fresh page via the buffer manager and initialises it as an empty
	 * {@link InternalPage}. The returned {@link RawPage} is <b>pinned</b>; the
	 * caller must call {@code
	 * markDirty} and {@code unpinPage} when done.
	 */
	private RawPage allocateRawInternalPage(int parentId) throws IOException {
		RawPage raw = bufferManager.createPage(fileId, null);
		int pageId = raw.getPid();
		byte[] rawData = raw.getByteArray(); // 4 096-byte buffer-pool array

		// Initialise header bytes into the fixed-size buffer-pool array.
		InternalPage temp = new InternalPage(pageId, parentId, degree, KEY_SIZE);
		System.arraycopy(temp.getData(), 0, rawData, 0, temp.getData().length);

		return raw; // PINNED
	}

	/**
	 * Allocates a fresh page via the buffer manager and initialises it as an empty
	 * {@link LeafPage}. The returned {@link RawPage} is <b>pinned</b>; the caller
	 * must call {@code markDirty} and {@code unpinPage} when done.
	 */
	private RawPage allocateRawLeafPage(int parentId, int prevPageId, int nextPageId) throws IOException {
		RawPage raw = bufferManager.createPage(fileId, null);
		int pageId = raw.getPid();
		byte[] rawData = raw.getByteArray();

		LeafPage temp = new LeafPage(pageId, parentId, prevPageId, nextPageId, degree, KEY_SIZE);
		System.arraycopy(temp.getData(), 0, rawData, 0, temp.getData().length);

		return raw; // PINNED
	}
}
