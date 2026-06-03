package storage;

public interface DataPage extends Page {
	/**
	 * Fetches a record from the page by its record ID.
	 *
	 * @param slotId
	 *            The ID of the record to retrieve.
	 * @return The Record object containing the data of a row.
	 */
	Record getRecord(int slotId);

	/**
	 * Inserts a new record into the page.
	 *
	 * @param record
	 *            The Record object containing the data to insert.
	 * @return The record ID of the inserted record, or -1 if the page is full
	 */
	int insertRecord(Record record);

	/**
	 * Check if the page is full.
	 *
	 * @return true if the page is full, false otherwise
	 */
	boolean isFull();
}
