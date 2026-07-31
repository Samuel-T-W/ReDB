import buffer.BufferManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Canonical sequential reference execution for query-result validation. */
final class SequentialBaselines {

    private static final Set<String> BASE_FILE_IDS =
            Set.of("movies.db", "workedon.db", "people.db", "title.idx");

    private SequentialBaselines() {
    }

    record Spec(String startRange, String endRange, boolean useIndex) {
    }

    static List<String> compute(
            Spec spec,
            int poolFrames,
            int frameBudget,
            Path outputPath) throws IOException {
        BufferManager manager = new BufferManager(poolFrames);
        RunQuery.registerCatalog(manager);
        long reportedCount = RunQuery.run(
                spec.startRange(),
                spec.endRange(),
                frameBudget,
                spec.useIndex(),
                manager,
                outputPath);

        List<String> rows = sortedRows(outputPath);
        if (reportedCount != rows.size()) {
            throw new IllegalStateException(
                    "sequential baseline row count disagrees with its output for " + spec);
        }
        if (manager.getTotalPinCount() != 0
                || !BASE_FILE_IDS.equals(manager.catalogFileNames())
                || manager.bufferedFileIds().stream()
                        .anyMatch(fileId -> !BASE_FILE_IDS.contains(fileId))) {
            throw new IllegalStateException(
                    "sequential baseline did not leave its BufferManager clean for " + spec);
        }
        return List.copyOf(rows);
    }

    static List<String> sortedRows(Path outputPath) throws IOException {
        List<String> rows = new ArrayList<>(
                Files.readAllLines(outputPath, StandardCharsets.UTF_8));
        Collections.sort(rows);
        return rows;
    }
}
