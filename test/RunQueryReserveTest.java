import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RunQueryReserveTest {
    @Test
    void reservePreservesSmallBlocksAndCapsEachAccessPath() {
        int[][] cases = {{3, 0, 0}, {50, 11, 11}, {100, 24, 24},
                {1000, 96, 64}, {388710, 96, 64}};
        for (int[] row : cases) {
            assertEquals(row[1], RunQuery.readAheadReserve(row[0], false), "scan budget " + row[0]);
            assertEquals(row[2], RunQuery.readAheadReserve(row[0], true), "index budget " + row[0]);
        }
    }
}
