package util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RecordUtilsTest {

    @Test
    void utf8RoundTripsThroughZeroPaddedField() {
        byte[] field = RecordUtils.toFixedBytes("Café", 8);

        assertEquals(8, field.length);
        assertEquals("Café", RecordUtils.fromFixedBytes(field));
    }

    @Test
    void encodedByteLengthMustFitField() {
        assertEquals(5, "Café".getBytes(StandardCharsets.UTF_8).length);
        assertThrows(
                IllegalArgumentException.class,
                () -> RecordUtils.toFixedBytes("Café", 4));
    }

    @Test
    void exactWidthValueIsAccepted() {
        assertEquals("é", RecordUtils.fromFixedBytes(RecordUtils.toFixedBytes("é", 2)));
    }
}
