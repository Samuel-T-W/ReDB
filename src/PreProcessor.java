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
    static final String FULL_TITLE_IDX = "title-full.idx";

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
                dataset.titleIndex);
    }

    /** Loads tables and the title index from the supplied CSV fixtures. */
    public static void run(String moviesCsv, String workedonCsv, String peopleCsv) throws IOException {
        run(moviesCsv, workedonCsv, peopleCsv, MOVIES_DB, WORKEDON_DB, PEOPLE_DB, TITLE_IDX);
    }

    private static void run(
            String moviesCsv,
            String workedonCsv,
            String peopleCsv,
            String moviesDb,
            String workedOnDb,
            String peopleDb,
            String titleIndex) throws IOException {
        PreProcessorUtils.resetFile(moviesDb);
        PreProcessorUtils.resetFile(workedOnDb);
        PreProcessorUtils.resetFile(peopleDb);
        PreProcessorUtils.resetFile(titleIndex);

        BufferManager bm = new BufferManager(BUFFER_SIZE);
        bm.register(new TableEntry(moviesDb, MOVIES_SCHEMA));
        bm.register(new TableEntry(workedOnDb, WORKEDON_SCHEMA));
        bm.register(new TableEntry(peopleDb, PEOPLE_SCHEMA));
        bm.register(new IndexEntry(titleIndex, MOVIES_SCHEMA.get("title")));

        int moviesPages = PreProcessorUtils.loadTable(bm, moviesCsv, moviesDb, MOVIES_SCHEMA);
        System.out.println("Movies loaded: " + moviesPages + " page(s)");

        int workedonPages = PreProcessorUtils.loadTable(bm, workedonCsv, workedOnDb, WORKEDON_SCHEMA);
        System.out.println("WorkedOn loaded: " + workedonPages + " page(s)");

        int peoplePages = PreProcessorUtils.loadTable(bm, peopleCsv, peopleDb, PEOPLE_SCHEMA);
        System.out.println("People loaded: " + peoplePages + " page(s)");

        PreProcessorUtils.buildIndex(
                bm,
                moviesPages,
                moviesDb,
                MOVIES_SCHEMA,
                titleIndex,
                "title",
                BTREE_DEGREE);
        System.out.println("Title index built.");
    }

    enum Dataset {
        SMALL(MOVIES_CSV, WORKEDON_CSV, PEOPLE_CSV, MOVIES_DB, WORKEDON_DB, PEOPLE_DB, TITLE_IDX),
        FULL(
                "data/imdb-full/title.csv",
                "data/imdb-full/workedon.csv",
                "data/imdb-full/name.csv",
                FULL_MOVIES_DB,
                FULL_WORKEDON_DB,
                FULL_PEOPLE_DB,
                FULL_TITLE_IDX);

        final String moviesCsv;
        final String workedOnCsv;
        final String peopleCsv;
        final String moviesDb;
        final String workedOnDb;
        final String peopleDb;
        final String titleIndex;

        Dataset(
                String moviesCsv,
                String workedOnCsv,
                String peopleCsv,
                String moviesDb,
                String workedOnDb,
                String peopleDb,
                String titleIndex) {
            this.moviesCsv = moviesCsv;
            this.workedOnCsv = workedOnCsv;
            this.peopleCsv = peopleCsv;
            this.moviesDb = moviesDb;
            this.workedOnDb = workedOnDb;
            this.peopleDb = peopleDb;
            this.titleIndex = titleIndex;
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
