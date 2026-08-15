import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_benchmark


class RunBenchmarkDatasetTest(unittest.TestCase):

    def test_dataset_defaults_to_small_and_accepts_full(self):
        with patch.object(sys, "argv", ["run_benchmark.py"]):
            self.assertEqual("small", run_benchmark.parse_args().dataset)
        with patch.object(sys, "argv", ["run_benchmark.py", "--dataset", "full"]):
            self.assertEqual("full", run_benchmark.parse_args().dataset)

    def test_full_worker_links_canonical_names_to_full_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            work_parent = root / "workers"
            work_parent.mkdir()
            for source in run_benchmark.DATABASE_SETS["full"].values():
                (root / source).touch()

            worker = run_benchmark.create_worker_dir(root, work_parent, "full", True)

            for canonical, source in run_benchmark.DATABASE_SETS["full"].items():
                self.assertTrue((worker / canonical).is_symlink())
                self.assertEqual(root / source, Path(os.readlink(worker / canonical)))

    def test_scan_benchmark_does_not_require_index(self):
        self.assertNotIn("title.idx", run_benchmark.database_files("small", False))
        self.assertEqual(
            "title-full.idx",
            run_benchmark.database_files("full", True)["title.idx"],
        )


if __name__ == "__main__":
    unittest.main()
