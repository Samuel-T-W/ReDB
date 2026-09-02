import java.util.List;

/**
 * Page-disjoint movie title ranges shared by concurrent-query tests.
 *
 * <p>The synthetic movie heap stores eight rows per page in CSV order. Gaps
 * between ranges keep concurrent queries from sharing an outer boundary page;
 * the BNL inner scans still contend on workedon.db and people.db from page 0.
 */
final class ConcurrentQueryRanges {

    private static final List<TitleRange> RANGES = List.of(
            new TitleRange("carmencita", "carmencita-0096"),
            new TitleRange("carmencita-0129", "carmencita-0256"),
            new TitleRange("carmencita-0289", "carmencita-0416"),
            new TitleRange("carmencita-0449", "carmencita-0576"),
            new TitleRange("carmencita-1001", "carmencita-1496"),
            new TitleRange("carmencita-1601", "carmencita-2096"),
            new TitleRange("carmencita-2201", "carmencita-2392"),
            new TitleRange("carmencita-3001", "carmencita-3992"));

    private ConcurrentQueryRanges() {
    }

    static int size() {
        return RANGES.size();
    }

    static TitleRange get(int index) {
        return RANGES.get(index);
    }

    static List<TitleRange> all() {
        return RANGES;
    }

    record TitleRange(String start, String end) {
    }
}
