from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from shared_engine_metadata import write_run_metadata


class SharedEngineMetadataTest(unittest.TestCase):
    def test_writes_exclusive_reproducibility_metadata(self):
        now = datetime(2026, 8, 7, 14, 30, 1, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as temporary, patch(
                "shared_engine_metadata._git_value",
                side_effect=[
                    "abc123", " M benchmark/file.py",
                    "abc123", " M benchmark/file.py"]), patch(
                "shared_engine_metadata.platform.platform",
                return_value="TestOS-1"), patch(
                "shared_engine_metadata.platform.node",
                return_value="test-host"), patch(
                "shared_engine_metadata.os.cpu_count",
                return_value=12), patch(
                "shared_engine_metadata._physical_memory_bytes",
                return_value=34_000_000_000), patch(
                "shared_engine_metadata._java_version",
                return_value='openjdk version "21"'):
            output = Path(temporary)

            write_run_metadata(output, output, True, now)

            metadata = json.loads((output / "metadata.json").read_text(encoding="utf-8"))
            self.assertEqual({
                "schema_version": 1,
                "created_at_utc": "2026-08-07T14:30:01Z",
                "git_commit": "abc123",
                "git_dirty": True,
                "platform": "TestOS-1",
                "hostname": "test-host",
                "logical_cpu_count": 12,
                "physical_memory_bytes": 34_000_000_000,
                "java_version": 'openjdk version "21"',
                "build_skipped": True,
            }, metadata)
            with self.assertRaises(FileExistsError):
                write_run_metadata(output, output, True, now)


if __name__ == "__main__":
    unittest.main()
