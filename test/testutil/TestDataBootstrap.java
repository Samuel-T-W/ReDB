package testutil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates deterministic CSV fixtures for tests that expect top-level data/*. */
public final class TestDataBootstrap {

	private static final Path DATA_DIR = Path.of("data");
	private static final Path REPORT_DIR = Path.of("report");
	private static final Path MOVIES_CSV = DATA_DIR.resolve("title.csv");
	private static final Path WORKEDON_CSV = DATA_DIR.resolve("workedon.csv");
	private static final Path PEOPLE_CSV = DATA_DIR.resolve("name.csv");
	private static final int ROW_COUNT = 4000;

	private static final Object LOCK = new Object();
	private static boolean initialized;

	private TestDataBootstrap() {
	}

	public static void ensure() throws IOException {
		synchronized (LOCK) {
			if (initialized && Files.exists(MOVIES_CSV) && Files.exists(WORKEDON_CSV) && Files.exists(PEOPLE_CSV)) {
				return;
			}
			Files.createDirectories(DATA_DIR);
			Files.createDirectories(REPORT_DIR);
			writeMovies();
			writeWorkedOn();
			writePeople();
			initialized = true;
		}
	}

	private static void writeMovies() throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(MOVIES_CSV, StandardCharsets.UTF_8)) {
			writer.write("movieId,title");
			writer.newLine();
			for (int i = 1; i <= ROW_COUNT; i++) {
				writer.write(movieId(i));
				writer.write(',');
				writer.write(title(i));
				writer.newLine();
			}
		}
	}

	private static void writeWorkedOn() throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(WORKEDON_CSV, StandardCharsets.UTF_8)) {
			writer.write("movieId,personId,category");
			writer.newLine();
			for (int i = 1; i <= ROW_COUNT; i++) {
				writer.write(movieId(i));
				writer.write(',');
				writer.write(personId(i));
				writer.write(",director");
				writer.newLine();
			}
		}
	}

	private static void writePeople() throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(PEOPLE_CSV, StandardCharsets.UTF_8)) {
			writer.write("personId,name");
			writer.newLine();
			for (int i = 1; i <= ROW_COUNT; i++) {
				writer.write(personId(i));
				writer.write(',');
				writer.write(personName(i));
				writer.newLine();
			}
		}
	}

	private static String movieId(int i) {
		return String.format("tt%07d", i);
	}

	private static String personId(int i) {
		return String.format("nm%07d", i);
	}

	private static String title(int i) {
		if (i == 1) {
			return "carmencita";
		}
		return String.format("carmencita-%04d", i);
	}

	private static String personName(int i) {
		return String.format("Person %04d", i);
	}
}
