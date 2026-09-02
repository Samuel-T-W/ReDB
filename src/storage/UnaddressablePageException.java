package storage;

/**
 * Thrown when a page cannot be addressed because it lies past the 32-bit page
 * id space: an out-of-range or wrapped page id, a file longer than
 * {@link RawPage#MAX_FILE_LEN}, or an allocation that would push the next page
 * id over {@link RawPage#MAX_PAGE_COUNT}.
 *
 * <p>This is the one addressability limit the engine has left, so it gets its
 * own type rather than sharing the generic {@link IllegalStateException} used
 * for corrupt-file conditions. Callers and tests can then tell "past the cap"
 * apart from "this file is malformed" without matching on message text.
 *
 * <p>Extends {@link IllegalStateException} so existing handlers still catch it.
 */
public class UnaddressablePageException extends IllegalStateException {

	public UnaddressablePageException(String message) {
		super(message);
	}
}
