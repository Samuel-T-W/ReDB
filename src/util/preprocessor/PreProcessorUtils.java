package util.preprocessor;

import buffer.BufferManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import storage.BTreeManager;
import storage.GenericPage;
import storage.GenericRecord;
import storage.K;
import storage.Page;
import storage.RecordId;
import util.RecordUtils;

public final class PreProcessorUtils {

    /**
     * How many individual skipped rows are named on stderr per file. A CSV loaded
     * against the wrong schema can overflow on every row, and millions of warnings
     * would bury the rest of the run; the tail is covered by the final count.
     */
    private static final int MAX_SKIP_WARNINGS = 20;

    private PreProcessorUtils() {
    }

    public static int loadTable(
            BufferManager bm,
            String csvPath,
            String fileId,
            Map<String, Integer> schema)
            throws IOException {
        try (BufferedReader br = Files.newBufferedReader(Path.of(csvPath), StandardCharsets.UTF_8)) {
            validateHeader(csvPath, schema, br.readLine());
            int numPages = 1;
            Page current = bm.createPage(fileId, null);
            GenericPage gp = new GenericPage(current, schema);
            String line;
            long lineNumber = 1; // the header line was just consumed
            long skipped = 0;
            while ((line = br.readLine()) != null) {
                lineNumber++;
                String[] cols = parseCsvLine(line);

                // A value too wide for its field is a data problem, not a load
                // failure: skip the row, keep loading, and account for it below.
                String overflow = describeOverflow(schema, cols);
                if (overflow != null) {
                    if (skipped < MAX_SKIP_WARNINGS) {
                        System.err.println(
                                "WARN " + csvPath + ":" + lineNumber + " skipped, " + overflow);
                    } else if (skipped == MAX_SKIP_WARNINGS) {
                        System.err.println(
                                "WARN " + csvPath + ": further per-row skip warnings suppressed");
                    }
                    skipped++;
                    continue;
                }
                GenericRecord rec = buildRecord(schema, cols);

                if (gp.insertRecord(rec) == -1) {
                    bm.unpinPage(fileId, current.getPid());
                    current = bm.createPage(fileId, null);
                    numPages++;
                    gp = new GenericPage(current, schema);
                    if (gp.insertRecord(rec) == -1) {
                        throw new IllegalArgumentException(
                                "Schema record is too large for an empty page: " + schema);
                    }
                }
                bm.markDirty(fileId, current.getPid());
            }

            bm.unpinPage(fileId, current.getPid());
            bm.force();
            if (skipped > 0) {
                System.err.println("WARN " + csvPath + ": skipped " + skipped
                        + " row(s) exceeding schema field widths; " + (lineNumber - 1 - skipped)
                        + " row(s) loaded");
            }
            return numPages;
        }
    }

    public static void resetFile(String path) throws IOException {
        File f = new File(path);
        f.delete();
        f.createNewFile();
    }

    public static BTreeManager buildIndex(
            BufferManager bm,
            int numPages,
            String tableFileId,
            Map<String, Integer> schema,
            String indexFileId,
            String fieldName,
            int degree)
            throws IOException {
        BTreeManager btree = new BTreeManager(degree, indexFileId, bm, schema.get(fieldName));

        for (int pid = 0; pid < numPages; pid++) {
            Page page = bm.getPage(tableFileId, pid);
            GenericPage gp = new GenericPage(page, schema);
            byte[] raw = gp.getByteArray();
            int numRecords = fromByteArray(Arrays.copyOfRange(raw, 0, 4));
            for (int slot = 0; slot < numRecords; slot++) {
                GenericRecord rec = (GenericRecord) gp.getRecord(slot);
                K key = new K(rec.getFieldBytes(fieldName));
                btree.insert(key, new RecordId(pid, slot));
            }
            bm.unpinPage(tableFileId, pid);
        }

        bm.force();
        return btree;
    }

    public static byte[] toFixedBytes(String s, int length) {
        return RecordUtils.toFixedBytes(s, length);
    }

    /**
     * Describes the first field whose value will not fit its fixed-length slot, or
     * null when the row is loadable.
     */
    private static String describeOverflow(Map<String, Integer> schema, String[] cols) {
        int i = 0;
        for (Map.Entry<String, Integer> field : schema.entrySet()) {
            String val = i < cols.length ? cols[i] : "";
            int byteCount = val.getBytes(StandardCharsets.UTF_8).length;
            if (byteCount > field.getValue()) {
                return field.getKey() + " requires " + byteCount
                        + " UTF-8 bytes but field allows " + field.getValue();
            }
            i++;
        }
        return null;
    }

    private static GenericRecord buildRecord(Map<String, Integer> schema, String[] cols) {
        GenericRecord rec = GenericRecord.create(schema);
        int i = 0;
        for (Map.Entry<String, Integer> field : schema.entrySet()) {
            String val = i < cols.length ? cols[i] : "";
            rec.set(field.getKey(), toFixedBytes(val, field.getValue()));
            i++;
        }
        return rec;
    }

    private static void validateHeader(
            String csvPath, Map<String, Integer> schema, String headerLine) throws IOException {
        if (headerLine == null) {
            throw new IOException(csvPath + " is empty");
        }
        String[] actual = parseCsvLine(headerLine);
        List<String> expected = new ArrayList<>(schema.keySet());
        if (actual.length > expected.size()
                || !expected.subList(0, actual.length).equals(Arrays.asList(actual))) {
            throw new IOException(
                    csvPath + " header must be a prefix of " + expected + ", got " + Arrays.toString(actual));
        }
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        while (i <= line.length()) {
            if (i < line.length() && line.charAt(i) == '"') {
                i++; // skip opening quote
                StringBuilder sb = new StringBuilder();
                while (i < line.length()) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                        } else {
                            i++; // skip closing quote
                            break;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                fields.add(sb.toString());
            } else {
                int start = i;
                while (i < line.length() && line.charAt(i) != ',') i++;
                fields.add(line.substring(start, i));
            }
            i++; // skip comma (or step past end)
        }
        return fields.toArray(new String[0]);
    }

    private static int fromByteArray(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }
}
