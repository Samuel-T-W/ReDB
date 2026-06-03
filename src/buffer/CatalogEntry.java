package buffer;

import java.io.File;

public sealed interface CatalogEntry permits TableEntry, IndexEntry {
	String fileName();

	File file();
}
