import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import trace.QueryTrace;
import trace.QueryTraceJson;
import trace.TraceEventType;

public class RunQueryTraceTest {
    @AfterEach
    void cleanup() throws Exception {
        DemoTraceExport.cleanupGeneratedFiles();
    }

    @Test
    void capturesRunQueryTraceFromEnginePath() throws Exception {
        DemoTraceExport.writeFixtureFiles();

        QueryTrace trace = RunQuery.capture(
                DemoTraceExport.DEFAULT_START,
                DemoTraceExport.DEFAULT_END,
                DemoTraceExport.DEFAULT_BUFFER_SIZE,
                DemoTraceExport.DEFAULT_INDEXED);

        assertEquals(QueryTrace.CURRENT_SCHEMA_VERSION, trace.schemaVersion());
        assertEquals("run_query", trace.run().command());
        assertEquals(DemoTraceExport.DEFAULT_START, trace.run().range().start());
        assertEquals(DemoTraceExport.DEFAULT_END, trace.run().range().end());
        assertEquals(DemoTraceExport.DEFAULT_BUFFER_SIZE, trace.run().bufferSize());
        assertTrue(trace.run().indexed());
        assertFalse(trace.events().isEmpty());
        assertEquals(TraceEventType.OPERATOR_OPEN, trace.events().get(0).type());
        assertEquals(TraceEventType.QUERY_COMPLETE, trace.events().get(trace.events().size() - 1).type());
        assertEquals(3, trace.summary().recordsEmitted());
        assertEquals(trace.summary().bufferMisses(), trace.summary().pagesRead());
        assertTrue(trace.summary().btreeNodeVisits() > 0);

        for (int i = 0; i < trace.events().size(); i++) {
            assertEquals(i, trace.events().get(i).seq());
            if (i > 0) {
                assertTrue(trace.events().get(i).timeMs() >= trace.events().get(i - 1).timeMs());
            }
        }
    }

    @Test
    void writesJsonTraceArtifact() throws Exception {
        DemoTraceExport.writeFixtureFiles();
        QueryTrace trace = RunQuery.capture(
                DemoTraceExport.DEFAULT_START,
                DemoTraceExport.DEFAULT_END,
                DemoTraceExport.DEFAULT_BUFFER_SIZE,
                DemoTraceExport.DEFAULT_INDEXED);
        Path output = Path.of("target/test-query-trace.json");

        QueryTraceJson.write(trace, output);

        assertTrue(Files.exists(output));
        assertEquals(1, QueryTraceJson.mapper().readTree(output.toFile()).get("schemaVersion").asInt());
    }
}
