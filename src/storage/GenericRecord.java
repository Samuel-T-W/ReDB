package storage;

import java.util.LinkedHashMap;
import java.util.Map;

public class GenericRecord implements Record {

	private Map<String, Integer> schema;
	private Map<String, Integer> offsets;
	private byte[] data;
	private int recordSize;

	// ===== Constructor for builder mode =====
	public GenericRecord(Map<String, Integer> schema) {
		initializeSchema(schema);
		this.data = new byte[recordSize];
	}

	// ===== Constructor for page read mode =====
	public GenericRecord(Map<String, Integer> schema, byte[] data) {
		initializeSchema(schema);

		if (data.length != recordSize) {
			throw new IllegalArgumentException("Invalid record size");
		}

		this.data = data;
	}

	// Schema + offset initialization
	private void initializeSchema(Map<String, Integer> schema) {
		this.schema = schema;
		this.offsets = new LinkedHashMap<>();

		int offset = 0;
		for (Map.Entry<String, Integer> entry : schema.entrySet()) {
			offsets.put(entry.getKey(), offset);
			offset += entry.getValue();
		}

		this.recordSize = offset;
	}

	// Static builder entry
	public static GenericRecord create(Map<String, Integer> schema) {
		return new GenericRecord(schema);
	}

	// Builder-style setter
	public GenericRecord set(String fieldName, byte[] value) {

		if (!schema.containsKey(fieldName)) {
			throw new IllegalArgumentException("Unknown field: " + fieldName);
		}

		int expectedSize = schema.get(fieldName);

		if (value.length != expectedSize) {
			throw new IllegalArgumentException("Size mismatch for field " + fieldName);
		}

		int offset = offsets.get(fieldName);
		System.arraycopy(value, 0, data, offset, expectedSize);

		return this; // enables chaining
	}

	public byte[] toByteArray() {
		return data;
	}

	public byte[] getFieldBytes(String fieldName) {
		if (!schema.containsKey(fieldName)) {
			throw new IllegalArgumentException("Unknown field: " + fieldName);
		}

		int size = schema.get(fieldName);
		int offset = offsets.get(fieldName);

		byte[] result = new byte[size];
		System.arraycopy(data, offset, result, 0, size);

		return result;
	}
}
