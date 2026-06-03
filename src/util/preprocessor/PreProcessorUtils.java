package util.preprocessor;

import buffer.BufferManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
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

    private PreProcessorUtils() {
    }

    public static int loadTable(
            BufferManager bm,
            String csvPath,
            String fileId,
            Map<String, Integer> schema)
            throws IOException {
        int numPages = 0;
        Page current = bm.createPage(fileId, null);
        numPages++;
        GenericPage gp = new GenericPage(current, schema);

        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line = br.readLine(); // skip header
            while ((line = br.readLine()) != null) {
                String[] cols = parseCsvLine(line);
                GenericRecord rec = buildRecord(schema, cols);

                if (gp.insertRecord(rec) == -1) {
                    bm.unpinPage(fileId, current.getPid());
                    current = bm.createPage(fileId, null);
                    numPages++;
                    gp = new GenericPage(current, schema);
                    gp.insertRecord(rec);
                }
                bm.markDirty(fileId, current.getPid());
            }
        }

        bm.unpinPage(fileId, current.getPid());
        bm.force();
        return numPages;
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
