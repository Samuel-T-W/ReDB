package storage;

/**
 * Represents a record identifier as a (pageId, slotId) pair. Uniquely
 * identifies a record across the entire database file.
 *
 * <p>
 * Since this is a Java {@code record}, {@code equals()}, {@code hashCode()},
 * and {@code
 * toString()} are auto-generated based on all components.
 *
 * @param pageId
 *            the ID of the page containing the record
 * @param slotId
 *            the slot index of the record within the page
 */
public record RecordId(int pageId, int slotId) {
}
