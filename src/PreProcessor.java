import buffer.BufferManager;
import catalog.ImdbSchemas;
import catalog.IndexEntry;
import catalog.TableEntry;
import java.io.IOException;
import java.util.Map;
import util.preprocessor.PreProcessorUtils;

public class PreProcessor {

    static final String MOVIES_CSV = "data/title.csv";
    static final String WORKEDON_CSV = "data/workedon.csv";
    static final String PEOPLE_CSV = "data/name.csv";

    static final String MOVIES_DB = "movies.db";
    static final String WORKEDON_DB = "workedon.db";
    static final String PEOPLE_DB = "people.db";
    static final String TITLE_IDX = "title.idx";

    static final String FULL_MOVIES_DB = "movies-full.db";
    static final String FULL_WORKEDON_DB = "workedon-full.db";
    static final String FULL_PEOPLE_DB = "people-full.db";

    static final Map<String, Integer> MOVIES_SCHEMA = ImdbSchemas.MOVIES;
    static final Map<String, Integer> WORKEDON_SCHEMA = ImdbSchemas.WORKED_ON;
    static final Map<String, Integer> PEOPLE_SCHEMA = ImdbSchemas.PEOPLE;

    private static final int BTREE_DEGREE = ImdbSchemas.TITLE_INDEX_DEGREE;
    private static final int BUFFER_SIZE = 100;

    public static void run() throws IOException {
        run(Dataset.SMALL);
    }

    public static void run(Dataset dataset) throws IOException {
        run(
                dataset.moviesCsv,
                dataset.workedOnCsv,
                dataset.peopleCsv,
                dataset.moviesDb,
                dataset.workedOnDb,
                dataset.peopleDb,
                dataset.titleIndex,
                dataset.moviesSchema,
                dataset.workedOnSchema,
                dataset.peopleSchema);
    }

    /** Loads tables and the title index from the supplied CSV fixtures. */
    public static void run(String moviesCsv, String workedonCsv, String peopleCsv) throws IOException {
        run(
                moviesCsv,
                workedonCsv,
                peopleCsv,
                MOVIES_DB,
                WORKEDON_DB,
                PEOPLE_DB,
                TITLE_IDX,
                MOVIES_SCHEMA,
                WORKEDON_SCHEMA,
                PEOPLE_SCHEMA);
    }

    private static void run(
            String moviesCsv,
            String workedonCsv,
            String peopleCsv,
            String moviesDb,
            String workedOnDb,
            String peopleDb,
            String titleIndex,
            Map<String, Integer> moviesSchema,
            Map<String, Integer> workedOnSchema,
            Map<String, Integer> peopleSchema) throws IOException {
        PreProcessorUtils.resetFile(moviesDb);
        PreProcessorUtils.resetFile(workedOnDb);
        PreProcessorUtils.resetFile(peopleDb);
        if (titleIndex != null) {
            PreProcessorUtils.resetFile(titleIndex);
        }

        BufferManager bm = new BufferManager(BUFFER_SIZE);
        bm.register(new TableEntry(moviesDb, moviesSchema));
        bm.register(new TableEntry(workedOnDb, workedOnSchema));
        bm.register(new TableEntry(peopleDb, peopleSchema));
        if (titleIndex != null) {
            bm.register(new IndexEntry(titleIndex, moviesSchema.get("title")));
        }

        int moviesPages = PreProcessorUtils.loadTable(bm, moviesCsv, moviesDb, moviesSchema);
        System.out.println("Movies loaded: " + moviesPages + " page(s)");

        int workedonPages = PreProcessorUtils.loadTable(bm, workedonCsv, workedOnDb, workedOnSchema);
        System.out.println("WorkedOn loaded: " + workedonPages + " page(s)");

        int peoplePages = PreProcessorUtils.loadTable(bm, peopleCsv, peopleDb, peopleSchema);
        System.out.println("People loaded: " + peoplePages + " page(s)");

        if (titleIndex != null) {
            PreProcessorUtils.buildIndex(
                    bm, moviesPages, moviesDb, moviesSchema, titleIndex, "title", BTREE_DEGREE);
            System.out.println("Title index built.");
        }
    }

    enum Dataset {
        SMALL(
                MOVIES_CSV, WORKEDON_CSV, PEOPLE_CSV,
                MOVIES_DB, WORKEDON_DB, PEOPLE_DB, TITLE_IDX,
                ImdbSchemas.MOVIES, ImdbSchemas.WORKED_ON, ImdbSchemas.PEOPLE),
        FULL(
                "data/imdb-benchmark/title.csv",
                "data/imdb-benchmark/workedon.csv",
                "data/imdb-benchmark/name.csv",
                FULL_MOVIES_DB,
                FULL_WORKEDON_DB,
                FULL_PEOPLE_DB,
                null,
                ImdbSchemas.BENCHMARK_MOVIES,
                ImdbSchemas.BENCHMARK_WORKED_ON,
                ImdbSchemas.BENCHMARK_PEOPLE);

        final String moviesCsv;
        final String workedOnCsv;
        final String peopleCsv;
        final String moviesDb;
        final String workedOnDb;
        final String peopleDb;
        final String titleIndex;
        final Map<String, Integer> moviesSchema;
        final Map<String, Integer> workedOnSchema;
        final Map<String, Integer> peopleSchema;

        Dataset(
                String moviesCsv,
                String workedOnCsv,
                String peopleCsv,
                String moviesDb,
                String workedOnDb,
                String peopleDb,
                String titleIndex,
                Map<String, Integer> moviesSchema,
                Map<String, Integer> workedOnSchema,
                Map<String, Integer> peopleSchema) {
            this.moviesCsv = moviesCsv;
            this.workedOnCsv = workedOnCsv;
            this.peopleCsv = peopleCsv;
            this.moviesDb = moviesDb;
            this.workedOnDb = workedOnDb;
            this.peopleDb = peopleDb;
            this.titleIndex = titleIndex;
            this.moviesSchema = moviesSchema;
            this.workedOnSchema = workedOnSchema;
            this.peopleSchema = peopleSchema;
        }

        static Dataset parse(String value) {
            return switch (value) {
                case "small" -> SMALL;
                case "full" -> FULL;
                default -> throw new IllegalArgumentException("Unknown dataset: " + value);
            };
        }
    }
}
