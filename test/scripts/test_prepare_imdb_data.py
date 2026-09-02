import csv
import gzip
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[2] / "scripts" / "prepare_imdb_data.py"
SPEC = importlib.util.spec_from_file_location("prepare_imdb_data", SCRIPT)
imdb = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(imdb)


class PrepareImdbDataTest(unittest.TestCase):
    def test_default_output_is_root_imdb_benchmark_directory(self):
        self.assertEqual(imdb.REPO_ROOT / "data" / "imdb-benchmark", imdb.DEFAULT_OUTPUT_DIR)

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.sources = self.root / "source"
        self.output = self.root / "output"
        self.sources.mkdir()

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_tsv(self, name, header, rows):
        with gzip.open(self.sources / name, "wt", encoding="utf-8", newline="") as output:
            for row in [header, *rows]:
                output.write("\t".join(row) + "\n")

    def test_happy_path_converts_three_rows_from_each_dataset(self):
        self.write_tsv("title.basics.tsv.gz",
            ["tconst", "titleType", "primaryTitle", "originalTitle", "isAdult",
             "startYear", "endYear", "runtimeMinutes", "genres"], [
                ["tt0000001", "movie", "Café, Noir", "Café Noir", "0", "2024", r"\N", "95", "Drama,Noir"],
                ["tt2", "short", '"Rolling in the Deep Dish', '"Rolling in the Deep Dish', "0", "1999", r"\N", "12", "Comedy"],
                ["tt3", "tvSeries", "Third", "Third", "1", "2010", "2012", "45", r"\N"],
            ])
        self.write_tsv("title.principals.tsv.gz",
            ["tconst", "ordering", "nconst", "category", "job", "characters"], [
                ["tt0000001", "1", "nm0000001", "director", r"\N", r"\N"],
                ["tt2", "2", "nm2", "writer", "screenplay", r"\N"],
                ["tt3", "3", "nm3", "actor", r"\N", '["Lead"]'],
            ])
        self.write_tsv("name.basics.tsv.gz",
            ["nconst", "primaryName", "birthYear", "deathYear", "primaryProfession", "knownForTitles"], [
                ["nm0000001", "Person One", "1970", r"\N", "director,writer", "tt0000001"],
                ["nm2", "Person Two", "1980", "2020", "writer", "tt2"],
                ["nm3", "Person Three", r"\N", r"\N", r"\N", "tt3"],
            ])

        imdb.run(self.sources, self.output)

        expected = {
            "title.csv": ["movieId,title", 'tt0000001,"Café, Noir"',
                'tt2,"""Rolling in the Deep Dish"', "tt3,Third"],
            "workedon.csv": ["movieId,personId,category",
                "tt0000001,nm0000001,director", "tt2,nm2,writer", "tt3,nm3,actor"],
            "name.csv": ["personId,name", "nm0000001,Person One",
                "nm2,Person Two", "nm3,Person Three"],
        }
        for name, lines in expected.items():
            self.assertEqual("\n".join(lines) + "\n", (self.output / name).read_text(encoding="utf-8"))

    def test_missing_column_preserves_existing_output(self):
        dataset = imdb.DATASETS[2]
        self.write_tsv(dataset.source, ["nconst"], [["nm1"]])
        output = self.root / dataset.output
        output.write_text("existing\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "missing columns"):
            imdb.convert(dataset, self.sources / dataset.source, output)
        self.assertEqual("existing\n", output.read_text(encoding="utf-8"))

    def test_output_values_reject_utf8_byte_overflow(self):
        with self.assertRaisesRegex(ValueError, "name exceeds 105 UTF-8 bytes: 106"):
            imdb.output_value("name", "é" * 53)


if __name__ == "__main__":
    unittest.main()
