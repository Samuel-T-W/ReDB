package buffer;

import java.io.File;
import java.util.Map;

public record TableEntry(String fileName, File file, Map<String, Integer> schema) implements CatalogEntry {
	public TableEntry(String fileName, Map<String, Integer> schema) {
		this(fileName, new File(fileName), schema);
	}
}
