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

    private int currentPage;
    private int currentSlot;
    private int currentNumRecords;

    public Scan(BufferManager bm, String fileId, Map<String, Integer> schema) {
        this.bm = Objects.requireNonNull(bm, "bm");
        this.fileId = Objects.requireNonNull(fileId, "fileId");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.numPages = calculatePageCount(fileId);
    }

    private int calculatePageCount(String fileId) {
        int fileLength = Math.toIntExact(new File(fileId).length());
        if (fileLength % RawPage.MAX_PAGE_LEN != 0) {
            throw new IllegalStateException("File size is not a multiple of pages");
        }
        return fileLength / RawPage.MAX_PAGE_LEN;
    }

    @Override
    public void open() {
        currentPage = 0;
        currentSlot = 0;
        currentNumRecords = -1;
    }

    @Override
    public GenericRecord next() {
        while (currentPage < numPages) {
            try {
                Page page = bm.getPage(fileId, currentPage);
                GenericPage gp = new GenericPage(page, schema);

                if (currentNumRecords < 0) {
                    byte[] raw = gp.getByteArray();
                    currentNumRecords = ByteBuffer.wrap(raw, 0, 4).getInt();
                }

                if (currentSlot < currentNumRecords) {
                    GenericRecord rec = (GenericRecord) gp.getRecord(currentSlot);
                    currentSlot++;
                    if (currentSlot >= currentNumRecords) {
                        bm.unpinPage(fileId, currentPage);
                        currentPage++;
                        currentSlot = 0;
                        currentNumRecords = -1;
                    } else {
                        bm.unpinPage(fileId, currentPage);
                    }
                    return rec;
                } else {
                    bm.unpinPage(fileId, currentPage);
                    currentPage++;
                    currentSlot = 0;
                    currentNumRecords = -1;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    @Override
    public void close() {
        // check if at least the first page was loaded
        if (currentNumRecords >= 0) {
            bm.unpinPage(fileId, currentPage);
        }
        currentPage = 0;
        currentSlot = 0;
        currentNumRecords = -1;
    }
}
