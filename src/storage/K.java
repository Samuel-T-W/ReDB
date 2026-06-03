package storage;

import java.nio.charset.StandardCharsets;

public class K {
	private char[] key;

	public K(String keyString) {
		this.key = keyString.toCharArray();
	}

	public K(byte[] keyByte) {
		String str = new String(keyByte, StandardCharsets.UTF_8);
		this.key = str.toCharArray();
	}

	byte[] getKeyAsBytes() {
		String keyString = new String(this.key);
		return keyString.getBytes(StandardCharsets.UTF_8);
	}
}
