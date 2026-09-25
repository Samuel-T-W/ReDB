import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from shared_engine_resources import run_monitored_java


PROCESS_METRICS = {
    "peak_rss_bytes": 1234,
    "minor_faults": 5,
    "major_faults": 1,
}
MONITOR_METRICS = {
    "aggregate_peak_rss_bytes": 1234,
    "memory_pid_samples_attempted": 3,
    "memory_pid_samples_successful": 3,
    "memory_pid_samples_failed": 0,
    "host_cpu_count": 8,
    "host_memory_total_bytes": 10000,
    "host_memory_available_min_bytes": 4000,
    "host_memory_available_mean_bytes": 5000.0,
    "host_swap_used_max_bytes": 0,
    "host_cpu_utilization_mean_pct": 25.0,
    "host_cpu_utilization_max_pct": 30.0,
    "host_loadavg_1m_max": 1.5,
    "host_samples_attempted": 3,
    "host_samples_successful": 3,
}


class SharedEngineResourcesTest(unittest.TestCase):
    def test_live_java_process_produces_plausible_resource_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary)
            source = config / "TinyMonitoredProcess.java"
            source.write_text(
                "public class TinyMonitoredProcess {\n"
                "  public static void main(String[] args) throws Exception {\n"
                "    byte[] data = new byte[1024 * 1024];\n"
                "    data[0] = 1;\n"
                "    Thread.sleep(750);\n"
                "  }\n"
                "}\n",
                encoding="utf-8")
            subprocess.run(["javac", source.name], cwd=config, check=True)

            run_monitored_java(
                ["java", "-cp", str(config), "TinyMonitoredProcess"], config, config)

            artifact = config / "resources.json"
            metrics = json.loads(artifact.read_text(encoding="utf-8"))
            print("Live resource metrics:", json.dumps(metrics, sort_keys=True))
            self.assertGreater(metrics["monitor"]["memory_pid_samples_attempted"], 0)
            self.assertGreater(metrics["process"]["peak_rss_bytes"], 0)
            self.assertTrue((config / "engine.stderr.log").is_file())

    def test_persists_existing_monitor_metrics_after_process_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary)
            process = Mock(pid=42)
            process.wait.return_value = 0
            monitor = Mock()
            monitor.unregister.return_value = PROCESS_METRICS
            monitor.group_metrics.return_value = MONITOR_METRICS
            with patch(
                    "shared_engine_resources.ProcessMemoryMonitor",
                    return_value=monitor) as monitor_class, patch(
                    "shared_engine_resources.subprocess.Popen",
                    return_value=process) as popen:

                run_monitored_java(["java", "EngineBenchmark"], config, config)

            monitor_class.assert_called_once_with(100)
            monitor.start.assert_called_once()
            monitor.register.assert_called_once_with(42)
            monitor.unregister.assert_called_once_with(42)
            monitor.drop.assert_called_once_with(42)
            monitor.stop.assert_called_once()
            self.assertEqual(subprocess.DEVNULL, popen.call_args.kwargs["stdout"])
            self.assertEqual(config / "engine.stderr.log", Path(popen.call_args.kwargs["stderr"].name))
            self.assertEqual({
                "sample_interval_ms": 100,
                "process": PROCESS_METRICS,
                "monitor": MONITOR_METRICS,
            }, json.loads((config / "resources.json").read_text(encoding="utf-8")))

    def test_nonzero_exit_preserves_artifacts_and_stops_monitor(self):
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary)
            process = Mock(pid=43)
            process.wait.return_value = 7
            monitor = Mock()
            monitor.unregister.return_value = PROCESS_METRICS
            monitor.group_metrics.return_value = MONITOR_METRICS
            with patch(
                    "shared_engine_resources.ProcessMemoryMonitor",
                    return_value=monitor), patch(
                    "shared_engine_resources.subprocess.Popen",
                    return_value=process):

                with self.assertRaisesRegex(subprocess.CalledProcessError, "exit status 7"):
                    run_monitored_java(["java"], config, config)

            monitor.stop.assert_called_once()
            self.assertTrue((config / "engine.stderr.log").is_file())
            self.assertTrue((config / "resources.json").is_file())


if __name__ == "__main__":
    unittest.main()
