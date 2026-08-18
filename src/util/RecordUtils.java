package util;

import java.nio.charset.StandardCharsets;

public final class RecordUtils {

    private RecordUtils() {
    }

    public static byte[] toFixedBytes(String value, int length) {
        byte[] result = new byte[length];
        byte[] src = value.getBytes(StandardCharsets.UTF_8);
        if (src.length > length) {
            throw new IllegalArgumentException(
                    "Value requires " + src.length + " UTF-8 bytes but field allows " + length);
        }
        System.arraycopy(src, 0, result, 0, src.length);
        return result;
    }

    public static String fromFixedBytes(byte[] value) {
        int length = value.length;
        while (length > 0 && value[length - 1] == 0) {
            length--;
        }
        return new String(value, 0, length, StandardCharsets.UTF_8);
    }
}
