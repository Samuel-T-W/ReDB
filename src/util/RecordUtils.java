package util;

import java.nio.charset.StandardCharsets;

public final class RecordUtils {

    private RecordUtils() {
    }

    public static byte[] toFixedBytes(String value, int length) {
        byte[] result = new byte[length];
        byte[] src = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, result, 0, Math.min(src.length, length));
        return result;
    }
}
