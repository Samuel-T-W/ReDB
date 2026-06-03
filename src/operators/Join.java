package operators;

import buffer.BufferManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import storage.GenericPage;
import storage.GenericRecord;
import storage.RawPage;
import storage.RecordId;

public class Join implements Operator {

    private final Operator outer;
    private final Operator inner;
    private final String outerJoinAttr;
    private final String innerJoinAttr;
    private final Map<String, Integer> outerSchema;
    private final Map<String, Integer> outputSchema;
    private final BufferManager bm;
    private final String blockFileId;
    private final int blockSize;

    // Per-block state — rebuilt on every new outer block
    private Map<String, List<RecordId>> hashTable;
    private Map<Integer, GenericPage> blockPageById;
    private List<Integer> currentBlockPageIds;

    // Iteration state — persists across next() calls
    private boolean outerExhausted;
    private GenericRecord currentInner;
    private Iterator<RecordId> matchIterator;
    private GenericRecord pendingInner;

    public Join(Operator outer,
                Operator inner,
                String outerJoinAttr,
                String innerJoinAttr,
                Map<String, Integer> outerSchema,
                Map<String, Integer> innerSchema,
                Map<String, Integer> outputSchema,
                BufferManager bm,
                String blockFileId,
                int blockSize) {
        this.outer = outer;
        this.inner = inner;
        this.outerJoinAttr = outerJoinAttr;
        this.innerJoinAttr = innerJoinAttr;
        this.outerSchema = outerSchema;
        this.outputSchema = outputSchema;
        this.bm = bm;
        this.blockFileId = blockFileId;
        this.blockSize = blockSize;
    }

    @Override
    public void open() {
        outer.open();
        inner.open();
        outerExhausted = false;
        currentInner = null;
        matchIterator = null;
        pendingInner = null;
        hashTable = new HashMap<>();
        blockPageById = new HashMap<>();
        currentBlockPageIds = new ArrayList<>();
        loadNextBlock();
    }

    @Override
    public GenericRecord next() {
        while (true) {
            // Phase 1: emit remaining matches for the current inner record
            if (matchIterator != null && matchIterator.hasNext()) {
                RecordId rid = matchIterator.next();
                GenericRecord outerRec = (GenericRecord) blockPageById.get(rid.pageId()).getRecord(rid.slotId());
                return buildOutput(outerRec, pendingInner);
            }
            matchIterator = null;
            pendingInner = null;

            // Phase 2: advance inner
            if (currentInner == null) {
                if (outerExhausted) return null;
                loadNextBlock();
                if (currentInner == null) return null;
                continue;
            }

            // Phase 3: probe hash table with current inner record
            String key = toKey(currentInner.getFieldBytes(innerJoinAttr));
            List<RecordId> matches = hashTable.get(key);
            if (matches != null && !matches.isEmpty()) {
                matchIterator = matches.iterator();
                pendingInner = currentInner;
            }
            currentInner = inner.next();
        }
    }

    @Override
    public void close() {
        unpinBlockPages();
        hashTable = null;
        blockPageById = null;
        matchIterator = null;
        pendingInner = null;
        currentInner = null;
        outerExhausted = false;
        outer.close();
        inner.close();
    }

    // -----------------------------------------------------------------------
    // Block management
    // -----------------------------------------------------------------------

    private void loadNextBlock() {
        unpinBlockPages();

        GenericPage currentBlockPage = null;
        int pagesLoaded = 0;
        boolean anyRecord = false;

        while (pagesLoaded < blockSize) {
            GenericRecord rec = outer.next();
            if (rec == null) {
                outerExhausted = true;
                break;
            }
            anyRecord = true;

            if (currentBlockPage == null || currentBlockPage.isFull()) {
                try {
                    RawPage raw = bm.createPage(blockFileId, null);
                    currentBlockPage = new GenericPage(raw, outerSchema);
                    currentBlockPageIds.add(raw.getPid());
                    blockPageById.put(raw.getPid(), currentBlockPage);
                    pagesLoaded++;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to allocate block page", e);
                }
            }

            int slotId = currentBlockPage.insertRecord(rec);
            int pid = currentBlockPage.getPid();
            String key = toKey(rec.getFieldBytes(outerJoinAttr));
            hashTable.computeIfAbsent(key, k -> new ArrayList<>())
                     .add(new RecordId(pid, slotId));
        }

        if (anyRecord) {
            inner.close();
            inner.open();
            currentInner = inner.next();
        } else {
            currentInner = null;
        }
    }

    private void unpinBlockPages() {
        for (int pid : currentBlockPageIds) {
            bm.unpinPage(blockFileId, pid);
        }
        currentBlockPageIds = new ArrayList<>();
        blockPageById = new HashMap<>();
        hashTable = new HashMap<>();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private GenericRecord buildOutput(GenericRecord outerRec, GenericRecord innerRec) {
        GenericRecord result = GenericRecord.create(outputSchema);
        for (String field : outputSchema.keySet()) {
            if (outerSchema.containsKey(field)) {
                result.set(field, outerRec.getFieldBytes(field));
            } else {
                result.set(field, innerRec.getFieldBytes(field));
            }
        }
        return result;
    }

    // ISO-8859-1 maps every byte value 0-255 to a unique char, so byte arrays
    // with the same content always produce equal strings (safe as a hash key).
    private static String toKey(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
