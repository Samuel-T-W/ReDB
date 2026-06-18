package catalog;

import java.io.File;

public record IndexEntry(String fileName, File file, int keySize) implements CatalogEntry {
	public IndexEntry(String fileName, int keySize) {
		this(fileName, new File(fileName), keySize);
	}
}
