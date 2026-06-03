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

    // Materializing-mode fields (null when pipelined)
    private final BufferManager bm;
    private final String tempFileId;

    private boolean materialized;
    private Scan tempScan;

    // Pipelined constructor
    public Project(Operator child,
                   Map<String, Integer> outputSchema) {
        this(child, outputSchema, null, null);
    }

    // Materializing constructor
    public Project(Operator child,
                   Map<String, Integer> outputSchema,
                   BufferManager bm,
                   String tempFileId) {
        this.child = child;
        this.outputSchema = outputSchema;
        this.bm = bm;
        this.tempFileId = tempFileId;
        this.materialized = false;
    }

    @Override
    public void open() {
        if (bm == null) {
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
        if (bm == null) {
            GenericRecord rec = child.next();
            if (rec == null) return null;
            return project(rec);
        }
        return tempScan.next();
    }

    @Override
    public void close() {
        if (bm == null) {
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
