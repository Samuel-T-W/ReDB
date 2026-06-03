import buffer.BufferManager;
import buffer.IndexEntry;
import buffer.TableEntry;
import java.io.IOException;
import java.util.LinkedHashMap;
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

    static final Map<String, Integer> MOVIES_SCHEMA = new LinkedHashMap<>();
    static final Map<String, Integer> WORKEDON_SCHEMA = new LinkedHashMap<>();
    static final Map<String, Integer> PEOPLE_SCHEMA = new LinkedHashMap<>();

    static {
        MOVIES_SCHEMA.put("movieId", 9);
        MOVIES_SCHEMA.put("title", 30);

        WORKEDON_SCHEMA.put("movieId", 9);
        WORKEDON_SCHEMA.put("personId", 10);
        WORKEDON_SCHEMA.put("category", 20);

        PEOPLE_SCHEMA.put("personId", 10);
        PEOPLE_SCHEMA.put("name", 105);
    }

    private static final int BTREE_DEGREE = 50;
    private static final int BUFFER_SIZE = 100;

    public static void run() throws IOException {
        PreProcessorUtils.resetFile(MOVIES_DB);
        PreProcessorUtils.resetFile(WORKEDON_DB);
        PreProcessorUtils.resetFile(PEOPLE_DB);
        PreProcessorUtils.resetFile(TITLE_IDX);

        BufferManager bm = new BufferManager(BUFFER_SIZE);
        bm.register(new TableEntry(MOVIES_DB, MOVIES_SCHEMA));
        bm.register(new TableEntry(WORKEDON_DB, WORKEDON_SCHEMA));
        bm.register(new TableEntry(PEOPLE_DB, PEOPLE_SCHEMA));
        bm.register(new IndexEntry(TITLE_IDX, MOVIES_SCHEMA.get("title")));

        int moviesPages = PreProcessorUtils.loadTable(bm, MOVIES_CSV, MOVIES_DB, MOVIES_SCHEMA);
        System.out.println("Movies loaded: " + moviesPages + " page(s)");

        int workedonPages = PreProcessorUtils.loadTable(bm, WORKEDON_CSV, WORKEDON_DB, WORKEDON_SCHEMA);
        System.out.println("WorkedOn loaded: " + workedonPages + " page(s)");

        int peoplePages = PreProcessorUtils.loadTable(bm, PEOPLE_CSV, PEOPLE_DB, PEOPLE_SCHEMA);
        System.out.println("People loaded: " + peoplePages + " page(s)");

        PreProcessorUtils.buildIndex(
                bm,
                moviesPages,
                MOVIES_DB,
                MOVIES_SCHEMA,
                TITLE_IDX,
                "title",
                BTREE_DEGREE);
        System.out.println("Title index built.");
    }
}
