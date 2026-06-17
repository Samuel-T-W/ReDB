"""Sample operating-system memory and page-fault data for query JVMs.

A single JVM can report its own heap, but only an external observer can sum
resident memory across several concurrent query processes. This monitor polls
that aggregate (and per-worker peaks) on a background thread.
"""

import platform
import subprocess
import threading
from pathlib import Path
import os
from typing import Sequence, TypedDict, overload

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
    host_cpu_count: int | None
    host_memory_total_bytes: int | None
    host_memory_available_min_bytes: int | None
    host_memory_available_mean_bytes: float | None
    host_swap_used_max_bytes: int | None
    host_cpu_utilization_mean_pct: float | None
    host_cpu_utilization_max_pct: float | None
    host_loadavg_1m_max: float | None
    host_samples_attempted: int
    host_samples_successful: int


class HostSample(TypedDict):
    """One host-level operating-system sample."""

    memory_total_bytes: int | None
    memory_available_bytes: int | None
    swap_used_bytes: int | None
    cpu_total: int | None
    cpu_idle: int | None
    loadavg_1m: float | None


@overload
def max_optional(current: int | None, candidate: int | None) -> int | None:
    ...


@overload
def max_optional(current: float | None, candidate: float | None) -> float | None:
    ...


def max_optional(
    current: int | float | None, candidate: int | float | None
) -> int | float | None:
    """Return the larger value while allowing either measurement to be None."""
    if candidate is None:
        return current
    return candidate if current is None else max(current, candidate)


@overload
def min_optional(current: int | None, candidate: int | None) -> int | None:
    ...


@overload
def min_optional(current: float | None, candidate: float | None) -> float | None:
    ...


def min_optional(
    current: int | float | None, candidate: int | float | None
) -> int | float | None:
    """Return the smaller value while allowing either measurement to be None."""
    if candidate is None:
        return current
    return candidate if current is None else min(current, candidate)


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
        self.host_cpu_count = os.cpu_count()
        self.host_memory_total_bytes: int | None = None
        self.host_memory_available_min_bytes: int | None = None
        self.host_memory_available_sum_bytes = 0
        self.host_memory_available_count = 0
        self.host_swap_used_max_bytes: int | None = None
        self.host_cpu_utilization_sum_pct = 0.0
        self.host_cpu_utilization_count = 0
        self.host_cpu_utilization_max_pct: float | None = None
        self.host_loadavg_1m_max: float | None = None
        self.host_samples_attempted = 0
        self.host_samples_successful = 0
        self.previous_cpu_total: int | None = None
        self.previous_cpu_idle: int | None = None
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        """Capture an initial CPU baseline, then start the sampling thread."""
        # Prime the CPU counters so the first recorded sample can already
        # produce a utilization delta instead of being skipped.
        baseline = self._read_host_sample()
        if baseline is not None:
            self.previous_cpu_total = baseline["cpu_total"]
            self.previous_cpu_idle = baseline["cpu_idle"]
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
            memory_available_mean = (
                self.host_memory_available_sum_bytes / self.host_memory_available_count
                if self.host_memory_available_count
                else None
            )
            cpu_utilization_mean = (
                self.host_cpu_utilization_sum_pct / self.host_cpu_utilization_count
                if self.host_cpu_utilization_count
                else None
            )
            return {
                "aggregate_peak_rss_bytes": self.peak_total_rss_bytes,
                "memory_pid_samples_attempted": self.pid_samples_attempted,
                "memory_pid_samples_successful": self.pid_samples_successful,
                "memory_pid_samples_failed": self.pid_samples_failed,
                "host_cpu_count": self.host_cpu_count,
                "host_memory_total_bytes": self.host_memory_total_bytes,
                "host_memory_available_min_bytes": self.host_memory_available_min_bytes,
                "host_memory_available_mean_bytes": memory_available_mean,
                "host_swap_used_max_bytes": self.host_swap_used_max_bytes,
                "host_cpu_utilization_mean_pct": cpu_utilization_mean,
                "host_cpu_utilization_max_pct": self.host_cpu_utilization_max_pct,
                "host_loadavg_1m_max": self.host_loadavg_1m_max,
                "host_samples_attempted": self.host_samples_attempted,
                "host_samples_successful": self.host_samples_successful,
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
        host_sample = self._read_host_sample()
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
            self._record_host_sample(host_sample)

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

    def _record_host_sample(self, sample: HostSample | None) -> None:
        """Merge one host-level sample into the group-level metrics."""
        self.host_samples_attempted += 1
        if sample is None or all(value is None for value in sample.values()):
            return
        self.host_samples_successful += 1

        memory_total = sample["memory_total_bytes"]
        if memory_total is not None:
            self.host_memory_total_bytes = memory_total

        memory_available = sample["memory_available_bytes"]
        if memory_available is not None:
            self.host_memory_available_min_bytes = min_optional(
                self.host_memory_available_min_bytes, memory_available
            )
            self.host_memory_available_sum_bytes += memory_available
            self.host_memory_available_count += 1

        self.host_swap_used_max_bytes = max_optional(
            self.host_swap_used_max_bytes, sample["swap_used_bytes"]
        )
        self.host_loadavg_1m_max = max_optional(
            self.host_loadavg_1m_max, sample["loadavg_1m"]
        )

        cpu_total = sample["cpu_total"]
        cpu_idle = sample["cpu_idle"]
        if cpu_total is None or cpu_idle is None:
            return
        if self.previous_cpu_total is not None and self.previous_cpu_idle is not None:
            total_delta = cpu_total - self.previous_cpu_total
            idle_delta = cpu_idle - self.previous_cpu_idle
            if total_delta > 0 and idle_delta >= 0:
                utilization = max(0.0, min(100.0, 100.0 * (1 - idle_delta / total_delta)))
                self.host_cpu_utilization_sum_pct += utilization
                self.host_cpu_utilization_count += 1
                self.host_cpu_utilization_max_pct = max_optional(
                    self.host_cpu_utilization_max_pct, utilization
                )
        self.previous_cpu_total = cpu_total
        self.previous_cpu_idle = cpu_idle

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
    def _read_host_sample() -> HostSample | None:
        """Read host CPU, memory, swap, and load metrics when available."""
        if platform.system() == "Linux":
            return ProcessMemoryMonitor._read_linux_host_sample()
        return ProcessMemoryMonitor._read_portable_host_sample()

    @staticmethod
    def _read_linux_host_sample() -> HostSample | None:
        """Read host-level metrics from Linux procfs."""
        try:
            meminfo = {}
            with Path("/proc/meminfo").open(encoding="utf-8") as handle:
                for line in handle:
                    key, rest = line.split(":", 1)
                    fields = rest.split()
                    if fields:
                        meminfo[key] = int(fields[0]) * 1024

            cpu_fields = Path("/proc/stat").read_text(encoding="utf-8").splitlines()[0].split()
            cpu_values = [int(value) for value in cpu_fields[1:]]
            cpu_idle = cpu_values[3] + (cpu_values[4] if len(cpu_values) > 4 else 0)
            cpu_total = sum(cpu_values)

            loadavg_1m = float(Path("/proc/loadavg").read_text(encoding="utf-8").split()[0])
            swap_total = meminfo.get("SwapTotal")
            swap_free = meminfo.get("SwapFree")
            swap_used = (
                swap_total - swap_free
                if swap_total is not None and swap_free is not None
                else None
            )
            return {
                "memory_total_bytes": meminfo.get("MemTotal"),
                "memory_available_bytes": meminfo.get("MemAvailable"),
                "swap_used_bytes": swap_used,
                "cpu_total": cpu_total,
                "cpu_idle": cpu_idle,
                "loadavg_1m": loadavg_1m,
            }
        except (OSError, ValueError, IndexError):
            return None

    @staticmethod
    def _read_portable_host_sample() -> HostSample | None:
        """Read the limited host metrics available outside Linux."""
        try:
            loadavg_1m = os.getloadavg()[0]
        except (AttributeError, OSError):
            loadavg_1m = None
        return {
            "memory_total_bytes": None,
            "memory_available_bytes": None,
            "swap_used_bytes": None,
            "cpu_total": None,
            "cpu_idle": None,
            "loadavg_1m": loadavg_1m,
        }

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
