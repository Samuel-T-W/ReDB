package trace;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Map;
import storage.RawPage;

/** Builds {@link TraceTable} metadata by reading page headers off disk. */
public final class TraceTables {

    private TraceTables() {}

    public static TraceTable forTable(String fileId, Map<String, Integer> schema) throws IOException {
        return new TraceTable(fileId, recordSize(schema), recordCount(fileId));
    }

    public static TraceTable forIndex(String fileId, int keySize) {
        return new TraceTable(fileId, keySize);
    }

    private static int recordSize(Map<String, Integer> schema) {
        return schema.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static long recordCount(String fileId) throws IOException {
        File file = new File(fileId);
        if (!file.exists() || file.length() == 0) {
            return 0;
        }
        if (file.length() % RawPage.MAX_PAGE_LEN != 0) {
            throw new IllegalStateException("File size is not a multiple of pages: " + fileId);
        }
        long count = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[4];
            int pageCount = Math.toIntExact(file.length() / RawPage.MAX_PAGE_LEN);
            for (int pageId = 0; pageId < pageCount; pageId++) {
                raf.seek(RawPage.getOffset(pageId));
                raf.readFully(header);
                count += ByteBuffer.wrap(header).getInt();
            }
        }
        return count;
    }
}
