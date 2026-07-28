import java.io.IOException;

/** Loads the deterministic CSVs required by query-concurrency tests. */
final class SyntheticQueryFixtures {

	private static final String MOVIES_CSV = "data/fixtures/synthetic/title.csv";
	private static final String WORKEDON_CSV = "data/fixtures/synthetic/workedon.csv";
	private static final String PEOPLE_CSV = "data/fixtures/synthetic/name.csv";

	private SyntheticQueryFixtures() {
	}

	static void load() throws IOException {
		PreProcessor.run(MOVIES_CSV, WORKEDON_CSV, PEOPLE_CSV);
	}
}
