package buffer;

import java.util.concurrent.CopyOnWriteArrayList;

/** Test double that records stall callbacks for assertions. */
public final class RecordingBufferPoolReporter implements BufferPoolReporter {

	public enum Phase {
		STARTED, RESOLVED, TIMEOUT, FAILED
	}

	public record Event(Phase phase, String stallId, long waitMillis, String snapshot) {
	}

	public final CopyOnWriteArrayList<Event> events = new CopyOnWriteArrayList<>();

	@Override
	public void onStallStarted(String stallId, int bufferSize, int waiterCount, String threadName,
			String pinnedFrameSnapshot) {
		events.add(new Event(Phase.STARTED, stallId, -1L, pinnedFrameSnapshot));
	}

	@Override
	public void onStallResolved(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
			String pinnedFrameSnapshot) {
		events.add(new Event(Phase.RESOLVED, stallId, waitMillis, pinnedFrameSnapshot));
	}

	@Override
	public void onStallTimeout(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
			String pinnedFrameSnapshot) {
		events.add(new Event(Phase.TIMEOUT, stallId, waitMillis, pinnedFrameSnapshot));
	}

	@Override
	public void onStallFailed(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
			String pinnedFrameSnapshot, Throwable cause) {
		events.add(new Event(Phase.FAILED, stallId, waitMillis, pinnedFrameSnapshot));
	}
}
