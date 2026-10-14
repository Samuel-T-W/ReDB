package operators;

import buffer.BufferManager;
import buffer.PageHandle;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import storage.GenericPage;
import storage.GenericRecord;
import storage.RawPage;

public class Scan implements Operator {

    // Decision (SAM-54): 32 pages = 128 KiB, sized as bandwidth x latency
    // on the EBS gp3 benchmark disk: 125 MiB/s x about 1 ms / 4 KiB is about 32.
    // It matches Linux's 128 KB default readahead and sits under PostgreSQL's
    // 256 KB scan ring and SQL Server's 64-page read-ahead. More depth would not
    // speed up this scan, so spare frames go to BNL blocks instead. Adaptive depth
    // and warm-starting the next stream at an inner rescan restart are deferred (SAM-54).
    // IndexScan read-ahead is also deferred (SAM-54).
    public static final int READ_AHEAD_PAGES = 32;

    private final BufferManager bm;
    private final String fileId;
    private final Map<String, Integer> schema;
    private final int numPages;

    // Iteration cursor, advanced across next() calls.
    private int currentPage;
    private int currentSlot;
    private int currentNumRecords; // record count of currentPage; -1 = header not yet read
    private int prefetchedThrough;

    public Scan(BufferManager bm, String fileId, Map<String, Integer> schema) {
        this.bm = Objects.requireNonNull(bm, "bm");
        this.fileId = Objects.requireNonNull(fileId, "fileId");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.numPages = RawPage.pageCount(fileId, new File(fileId).length());
    }

    @Override
    public void open() {
        currentPage = 0;
        currentSlot = 0;
        currentNumRecords = -1; // -1 to signify unset value
        prefetchedThrough = -1;
    }

    @Override
    public GenericRecord next() {
        // Loops only to skip empty pages; a page with records returns on the first
        // iteration.
        while (currentPage < numPages) {
            int pageId = currentPage;
            try {
                PageHandle handle = bm.pinPage(fileId, pageId);
                try {
                    GenericPage gp = new GenericPage(handle.page(), schema);

                    if (currentNumRecords == -1) {
                        // Sliding window, issued once per page. Prefetch is best effort;
                        // pinPage above remains responsible for providing the current page.
                        int through = pageId + Math.min(READ_AHEAD_PAGES, numPages - 1 - pageId);
                        for (int next = Math.max(pageId + 1, prefetchedThrough + 1); next <= through; next++) {
                            bm.prefetch(fileId, next);
                        }
                        prefetchedThrough = through;
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
                    bm.unpinPage(handle);
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
        prefetchedThrough = -1;
    }
}
