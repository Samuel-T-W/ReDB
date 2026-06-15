"""Sample operating-system memory and page-fault data for query JVMs.

A single JVM can report its own heap, but only an external observer can sum
resident memory across several concurrent query processes. This monitor polls
that aggregate (and per-worker peaks) on a background thread.
"""

import platform
import subprocess
import threading
from pathlib import Path
from typing import Sequence, TypedDict


class ProcessSample(TypedDict):
    """One operating-system measurement for a query process."""

    rss_bytes: int
    minor_faults: int | None
    major_faults: int | None


class WorkerMetrics(TypedDict):
    """Peak operating-system measurements collected for one query process."""

    peak_rss_bytes: int | None
    minor_faults: int | None
    major_faults: int | None


class GroupMetrics(TypedDict):
    """Aggregate peak memory and per-PID sampling counts for one worker group."""

    aggregate_peak_rss_bytes: int | None
    memory_pid_samples_attempted: int
    memory_pid_samples_successful: int
    memory_pid_samples_failed: int


def max_optional(current: int | None, candidate: int | None) -> int | None:
    """Return the larger value while allowing either measurement to be None."""
    if candidate is None:
        return current
    return candidate if current is None else max(current, candidate)


class ProcessMemoryMonitor:
    """Periodically sample memory and page-fault data for active query JVMs."""

    def __init__(self, sample_interval_ms: int) -> None:
        """Set up monitor state and a background sampler at the given interval."""
        self.sample_interval_seconds = sample_interval_ms / 1000
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.workers: dict[int, WorkerMetrics] = {}
        self.peak_total_rss_bytes: int | None = None
        self.pid_samples_attempted = 0
        self.pid_samples_successful = 0
        self.pid_samples_failed = 0
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        """Start the background sampling thread."""
        self.thread.start()

    def stop(self) -> None:
        """Request a final sample and wait for the sampling thread to finish."""
        self.stop_event.set()
        self.thread.join()

    def register(self, pid: int) -> None:
        """Begin tracking a worker PID and immediately attempt its first sample."""
        with self.lock:
            self.workers[pid] = {
                "peak_rss_bytes": None,
                "minor_faults": None,
                "major_faults": None,
            }
        self.sample_pid(pid)

    def unregister(self, pid: int) -> WorkerMetrics:
        """Stop tracking a PID and return the peak metrics collected for it."""
        with self.lock:
            return self.workers.pop(
                pid,
                {
                    "peak_rss_bytes": None,
                    "minor_faults": None,
                    "major_faults": None,
                },
            )

    def drop(self, pid: int) -> None:
        """Discard a PID without returning its metrics (cleanup on failure)."""
        with self.lock:
            self.workers.pop(pid, None)

    def sample_pid(self, pid: int) -> None:
        """Sample one PID and merge the reading into that worker's peak values."""
        sample = self._read_process(pid)
        with self.lock:
            self._record_sample_result(sample)
            if sample is not None:
                worker = self.workers.get(pid)
                if worker is not None:
                    self._update_worker(worker, sample)


 
    def group_metrics(self) -> GroupMetrics:
        """Return aggregate peak memory and per-PID sampling counts."""
        with self.lock:
            return {
                "aggregate_peak_rss_bytes": self.peak_total_rss_bytes,
                "memory_pid_samples_attempted": self.pid_samples_attempted,
                "memory_pid_samples_successful": self.pid_samples_successful,
                "memory_pid_samples_failed": self.pid_samples_failed,
            }

    def _run(self) -> None:
        """Continuously sample workers until stop() signals the monitor."""
        while not self.stop_event.is_set():
            self._sample_all()
            self.stop_event.wait(self.sample_interval_seconds)
        self._sample_all()

    def _sample_all(self) -> None:
        """Read all active workers and update per-worker and aggregate peaks."""
        with self.lock:
            pids = list(self.workers)
        if platform.system() == "Linux":
            samples = [(pid, self._read_linux_process(pid)) for pid in pids]
        else:
            process_samples = self._read_ps_processes(pids)
            samples = [(pid, process_samples.get(pid)) for pid in pids]
        valid_samples = [(pid, sample) for pid, sample in samples if sample is not None]
        total_rss_bytes = sum(sample["rss_bytes"] for _, sample in valid_samples)

        with self.lock:
            for pid, sample in samples:
                self._record_sample_result(sample)
                if sample is not None:
                    worker = self.workers.get(pid)
                    if worker is not None:
                        self._update_worker(worker, sample)
            if valid_samples:
                self.peak_total_rss_bytes = max_optional(
                    self.peak_total_rss_bytes, total_rss_bytes
                )

# ------------ here ------------------- 

    def _record_sample_result(self, sample: ProcessSample | None) -> None:
        """Record whether one attempted PID sample returned every metric."""
        self.pid_samples_attempted += 1
        if (
            sample is not None
            and sample["minor_faults"] is not None
            and sample["major_faults"] is not None
        ):
            self.pid_samples_successful += 1
        else:
            self.pid_samples_failed += 1

    @staticmethod
    def _update_worker(worker: WorkerMetrics, sample: ProcessSample) -> None:
        """Merge one process sample into a worker's running maximum counters."""
        worker["peak_rss_bytes"] = max_optional(
            worker["peak_rss_bytes"], sample["rss_bytes"]
        )
        worker["minor_faults"] = max_optional(
            worker["minor_faults"], sample["minor_faults"]
        )
        worker["major_faults"] = max_optional(
            worker["major_faults"], sample["major_faults"]
        )

    @staticmethod
    def _read_process(pid: int) -> ProcessSample | None:
        """Read process metrics using Linux procfs or the portable ps fallback."""
        if platform.system() == "Linux":
            return ProcessMemoryMonitor._read_linux_process(pid)
        return ProcessMemoryMonitor._read_ps_process(pid)

    @staticmethod
    def _read_linux_process(pid: int) -> ProcessSample | None:
        """Read RSS and page faults for a PID from Linux procfs."""
        try:
            rss_bytes = None
            with Path(f"/proc/{pid}/status").open(encoding="utf-8") as handle:
                for line in handle:
                    if line.startswith("VmRSS:"):
                        _, value, _ = line.split()
                        rss_bytes = int(value) * 1024
                        break
            if rss_bytes is None:
                return None

            stat = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
            fields = stat[stat.rfind(")") + 2 :].split()
            return {
                "rss_bytes": rss_bytes,
                "minor_faults": int(fields[7]),
                "major_faults": int(fields[9]),
            }
        except (OSError, ValueError, IndexError):
            return None

    @staticmethod
    def _read_ps_process(pid: int) -> ProcessSample | None:
        """Read one process by delegating to the batched ps implementation."""
        return ProcessMemoryMonitor._read_ps_processes([pid]).get(pid)

    @staticmethod
    def _read_ps_processes(pids: Sequence[int]) -> dict[int, ProcessSample]:
        """Read RSS and, when supported, page faults for several PIDs via ps."""
        if not pids:
            return {}
        pid_list = ",".join(str(pid) for pid in pids)
        try:
            completed = subprocess.run(
                ["ps", "-o", "pid=,rss=,minflt=,majflt=", "-p", pid_list],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
            if completed.returncode == 0:
                samples = {}
                for line in completed.stdout.splitlines():
                    fields = line.split()
                    if len(fields) == 4:
                        samples[int(fields[0])] = {
                            "rss_bytes": int(fields[1]) * 1024,
                            "minor_faults": int(fields[2]),
                            "major_faults": int(fields[3]),
                        }
                if samples:
                    return samples
        except (OSError, ValueError):
            pass

        try:
            completed = subprocess.run(
                ["ps", "-o", "pid=,rss=", "-p", pid_list],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
            if completed.returncode == 0:
                samples = {}
                for line in completed.stdout.splitlines():
                    fields = line.split()
                    if len(fields) == 2:
                        samples[int(fields[0])] = {
                            "rss_bytes": int(fields[1]) * 1024,
                            "minor_faults": None,
                            "major_faults": None,
                        }
                return samples
        except (OSError, ValueError):
            pass
        return {}
