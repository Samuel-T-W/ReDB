package operators;

import buffer.BufferManager;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import storage.GenericPage;
import storage.GenericRecord;
import storage.Page;
import storage.RawPage;

public class Scan implements Operator {

    private final BufferManager bm;
    private final String fileId;
    private final Map<String, Integer> schema;
    private final int numPages;

    // Iteration cursor, advanced across next() calls.
    private int currentPage;
    private int currentSlot;
    private int currentNumRecords; // record count of currentPage; -1 = header not yet read

    public Scan(BufferManager bm, String fileId, Map<String, Integer> schema) {
        this.bm = Objects.requireNonNull(bm, "bm");
        this.fileId = Objects.requireNonNull(fileId, "fileId");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.numPages = calculatePageCount(fileId);
    }

    private int calculatePageCount(String fileId) {
        long fileLength = new File(fileId).length();
        if (fileLength % RawPage.MAX_PAGE_LEN != 0) {
            throw new IllegalStateException("File size is not a multiple of pages");
        }
        return Math.toIntExact(fileLength / RawPage.MAX_PAGE_LEN);
    }

    @Override
    public void open() {
        currentPage = 0;
        currentSlot = 0;
        currentNumRecords = -1; // -1 to signify unset value
    }

    @Override
    public GenericRecord next() {
        // Loops only to skip empty pages; a page with records returns on the first
        // iteration.
        while (currentPage < numPages) {
            int pageId = currentPage;
            try {
                Page page = bm.getPage(fileId, pageId);
                try {
                    GenericPage gp = new GenericPage(page, schema);

                    if (currentNumRecords == -1) {
                        byte[] raw = gp.getByteArray();
                        currentNumRecords = ByteBuffer.wrap(raw, 0, 4).getInt();
                    }

                    if (currentSlot < currentNumRecords) {
                        GenericRecord rec = (GenericRecord) gp.getRecord(currentSlot);
                        currentSlot++;
                        if (currentSlot >= currentNumRecords) {
                            // Last record on this page; advance cursor to the next page.
                            currentPage++;
                            currentSlot = 0;
                            currentNumRecords = -1;
                        }
                        return rec;
                    } else {
                        // Empty page: advance and loop to try the next one.
                        currentPage++;
                        currentSlot = 0;
                        currentNumRecords = -1;
                    }
                } finally {
                    bm.unpinPage(fileId, pageId);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    @Override
    public void close() {
        currentPage = 0;
        currentSlot = 0;
        currentNumRecords = -1;
    }
}
