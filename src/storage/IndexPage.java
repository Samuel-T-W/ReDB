package storage;

public interface IndexPage extends Page {
	/** returns true if node is a leaf or false if not */
	boolean isLeafNode();

	/**
	 * get capacity of the array
	 *
	 * @return
	 */
	int getCapacity();

	/**
	 * get size of item list
	 *
	 * @return
	 */
	int getSize();

	/**
	 * check weather the array is full
	 *
	 * @return
	 */
	boolean isFull();
}
