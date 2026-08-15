package catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed-width physical schemas shared by IMDb preprocessing and queries. */
public final class ImdbSchemas {

    public static final int MOVIE_ID_BYTES = 12;
    public static final int PERSON_ID_BYTES = 12;
    public static final int TITLE_BYTES = 512;
    public static final int NAME_BYTES = 128;
    public static final int CATEGORY_BYTES = 24;
    public static final int TITLE_INDEX_DEGREE = 7;

    public static final Map<String, Integer> MOVIES;
    public static final Map<String, Integer> WORKED_ON;
    public static final Map<String, Integer> PEOPLE;

    static {
        Map<String, Integer> movies = new LinkedHashMap<>();
        movies.put("movieId", MOVIE_ID_BYTES);
        movies.put("title", TITLE_BYTES);
        movies.put("startYear", 4);
        movies.put("endYear", 4);
        movies.put("isAdult", 1);
        movies.put("originalTitle", 512);
        movies.put("titleType", 16);
        movies.put("runtimeMinutes", 8);
        movies.put("genres", 64);
        MOVIES = Collections.unmodifiableMap(movies);

        Map<String, Integer> workedOn = new LinkedHashMap<>();
        workedOn.put("movieId", MOVIE_ID_BYTES);
        workedOn.put("personId", PERSON_ID_BYTES);
        workedOn.put("category", CATEGORY_BYTES);
        workedOn.put("ordering", 4);
        workedOn.put("job", 320);
        WORKED_ON = Collections.unmodifiableMap(workedOn);

        Map<String, Integer> people = new LinkedHashMap<>();
        people.put("personId", PERSON_ID_BYTES);
        people.put("name", NAME_BYTES);
        people.put("birthYear", 4);
        people.put("deathYear", 4);
        people.put("primaryProfession", 96);
        PEOPLE = Collections.unmodifiableMap(people);
    }

    private ImdbSchemas() {
    }
}
