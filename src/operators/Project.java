package operators;

import buffer.BufferManager;
import buffer.TableEntry;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import storage.GenericPage;
import storage.GenericRecord;
import storage.RawPage;

public class Project implements Operator {

    private final Operator child;
    private final Map<String, Integer> outputSchema;

    // When true, the child is drained into a temp file on open() and rows are
    // served by scanning that file (so repeated rescans, e.g. a BNL inner side,
    // are cheap). When false, projection is pipelined one record at a time.
    private final boolean materialize;

    // Materializing-mode fields (null when pipelined)
    private final BufferManager bm;
    private final String tempFileId;

    private boolean materialized;
    private Scan tempScan;

    // Pipelined: stream one record at a time, no buffering. Use when the parent
    // reads this projection exactly once (e.g. the final output to stdout).
    public Project(Operator child,
                   Map<String, Integer> outputSchema) {
        this.child = child;
        this.outputSchema = outputSchema;
        this.materialize = false;
        this.bm = null;
        this.tempFileId = null;
        this.materialized = false;
    }

    // Materializing: drain child to tempFileId once, then serve via a Scan. Use
    // when the parent rescans this projection (e.g. a BNL join's inner input).
    // Java has no named constructors, so the chosen mode is implied by which
    // overload the call site invokes; `materialize` makes the mode explicit.
    public Project(Operator child,
                   Map<String, Integer> outputSchema,
                   BufferManager bm,
                   String tempFileId) {
        this.child = child;
        this.outputSchema = outputSchema;
        this.materialize = true;
        this.bm = bm;
        this.tempFileId = tempFileId;
        this.materialized = false;
    }

    @Override
    public void open() {
        if (!materialize) {
            child.open();
            return;
        }

        if (!materialized) {
            materialize();
            materialized = true;
        }

        tempScan.open();
    }

    @Override
    public GenericRecord next() {
        if (!materialize) {
            GenericRecord rec = child.next();
            if (rec == null) return null;
            return project(rec);
        }
        return tempScan.next();
    }

    @Override
    public void close() {
        if (!materialize) {
            child.close();
        } else {
            tempScan.close();
        }
    }

    /** Deletes the materialized temp file. Call once after the query finishes. */
    public void cleanup() {
        new File(tempFileId).delete();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private GenericRecord project(GenericRecord rec) {
        GenericRecord out = GenericRecord.create(outputSchema);
        for (String field : outputSchema.keySet()) {
            out.set(field, rec.getFieldBytes(field));
        }
        return out;
    }

    private void materialize() {
        try {
            // Ensure temp file exists and is empty
            File f = new File(tempFileId);
            f.delete();
            f.createNewFile();

            // Register temp file in catalog if not already registered
            if (bm.getCatalogEntry(tempFileId) == null) {
                bm.register(new TableEntry(tempFileId, outputSchema));
            }

            child.open();

            RawPage currentRaw = bm.createPage(tempFileId, null);
            GenericPage currentPage = new GenericPage(currentRaw, outputSchema);

            GenericRecord rec;
            while ((rec = child.next()) != null) {
                GenericRecord projected = project(rec);
                if (currentPage.insertRecord(projected) == -1) {
                    bm.markDirty(tempFileId, currentRaw.getPid());
                    bm.unpinPage(tempFileId, currentRaw.getPid());
                    currentRaw = bm.createPage(tempFileId, null);
                    currentPage = new GenericPage(currentRaw, outputSchema);
                    currentPage.insertRecord(projected);
                }
            }
            bm.markDirty(tempFileId, currentRaw.getPid());
            bm.unpinPage(tempFileId, currentRaw.getPid());
            bm.force();

            child.close();

            // Build the scan after force() so the file has the correct size on disk
            tempScan = new Scan(bm, tempFileId, outputSchema);
        } catch (IOException e) {
            throw new RuntimeException("Project materialization failed", e);
        }
    }
}
