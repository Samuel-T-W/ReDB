package catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed-width physical schemas shared by IMDb preprocessing and queries. */
public final class ImdbSchemas {

    public static final int TITLE_INDEX_DEGREE = 50;
    public static final String BENCHMARK_DIRECTOR = "6";

    public static final Map<String, Integer> MOVIES;
    public static final Map<String, Integer> WORKED_ON;
    public static final Map<String, Integer> PEOPLE;
    public static final Map<String, Integer> BENCHMARK_MOVIES;
    public static final Map<String, Integer> BENCHMARK_WORKED_ON;
    public static final Map<String, Integer> BENCHMARK_PEOPLE;

    static {
        Map<String, Integer> movies = new LinkedHashMap<>();
        movies.put("movieId", 9);
        movies.put("title", 30);
        MOVIES = Collections.unmodifiableMap(movies);

        Map<String, Integer> workedOn = new LinkedHashMap<>();
        workedOn.put("movieId", 9);
        workedOn.put("personId", 10);
        workedOn.put("category", 20);
        WORKED_ON = Collections.unmodifiableMap(workedOn);

        Map<String, Integer> people = new LinkedHashMap<>();
        people.put("personId", 10);
        people.put("name", 105);
        PEOPLE = Collections.unmodifiableMap(people);

        Map<String, Integer> benchmarkMovies = new LinkedHashMap<>();
        benchmarkMovies.put("movieId", 8);
        benchmarkMovies.put("title", 482);
        BENCHMARK_MOVIES = Collections.unmodifiableMap(benchmarkMovies);

        Map<String, Integer> benchmarkWorkedOn = new LinkedHashMap<>();
        benchmarkWorkedOn.put("movieId", 8);
        benchmarkWorkedOn.put("personId", 8);
        benchmarkWorkedOn.put("category", 1);
        BENCHMARK_WORKED_ON = Collections.unmodifiableMap(benchmarkWorkedOn);

        Map<String, Integer> benchmarkPeople = new LinkedHashMap<>();
        benchmarkPeople.put("personId", 8);
        benchmarkPeople.put("name", 105);
        BENCHMARK_PEOPLE = Collections.unmodifiableMap(benchmarkPeople);
    }

    private ImdbSchemas() {
    }
}
