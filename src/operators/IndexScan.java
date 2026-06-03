package operators;

import buffer.BufferManager;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import storage.BTreeManager;
import storage.GenericPage;
import storage.GenericRecord;
import storage.K;
import storage.Page;
import storage.RecordId;

public class IndexScan implements Operator {

    private final BufferManager bm;
    private final String tableFileId;
    private final Map<String, Integer> schema;
    private final BTreeManager btree;
    private final K startKey;
    private final K endKey;

    private Iterator<RecordId> rids;

    public IndexScan(
            BufferManager bm,
            String tableFileId,
            Map<String, Integer> schema,
            BTreeManager btree,
            K startKey,
            K endKey) {
        this.bm = bm;
        this.tableFileId = tableFileId;
        this.schema = schema;
        this.btree = btree;
        this.startKey = startKey;
        this.endKey = endKey;
    }

    @Override
    public void open() {
        rids = btree.rangeSearch(startKey, endKey);
    }

    @Override
    public GenericRecord next() {
        if (rids == null || !rids.hasNext()) return null;
        RecordId rid = rids.next();
        try {
            Page page = bm.getPage(tableFileId, rid.pageId());
            GenericRecord rec = (GenericRecord) new GenericPage(page, schema).getRecord(rid.slotId());
            bm.unpinPage(tableFileId, rid.pageId());
            return rec;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        rids = null;
    }
}
