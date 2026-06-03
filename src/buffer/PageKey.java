package buffer;

/**
 * Uniquely identifies a page within the buffer manager by combining a file
 * identifier with a page number.
 *
 * <p>
 * Used as the lookup key in the buffer pool's page table.
 *
 * @param fileId
 *            the identifier of the database file containing the page
 * @param pageId
 *            the page number within that file
 */
public record PageKey(String fileId, int pageId) {
}
