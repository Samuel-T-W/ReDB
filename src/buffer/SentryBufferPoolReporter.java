package buffer;

import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Message;
import java.util.List;

/**
 * Reports buffer-pool eviction stalls as Sentry Issues (not log messages).
 * No-ops when Sentry is not enabled, so tests/CI without a DSN send nothing.
 */
public final class SentryBufferPoolReporter implements BufferPoolReporter {

	@Override
	public void onStallStarted(String stallId, int bufferSize, int waiterCount, String threadName,
			String pinnedFrameSnapshot) {
		capture("started", SentryLevel.WARNING, stallId, bufferSize, waiterCount, threadName, null, pinnedFrameSnapshot,
				null);
	}

	@Override
	public void onStallResolved(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
			String pinnedFrameSnapshot) {
		capture("resolved", SentryLevel.WARNING, stallId, bufferSize, waiterCount, threadName, waitMillis,
				pinnedFrameSnapshot, null);
	}

	@Override
	public void onStallTimeout(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
			String pinnedFrameSnapshot) {
		capture("timeout", SentryLevel.ERROR, stallId, bufferSize, waiterCount, threadName, waitMillis,
				pinnedFrameSnapshot, null);
	}

	@Override
	public void onStallFailed(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
			String pinnedFrameSnapshot, Throwable cause) {
		capture("failed", SentryLevel.ERROR, stallId, bufferSize, waiterCount, threadName, waitMillis,
				pinnedFrameSnapshot, cause);
	}

	private static void capture(String phase, SentryLevel level, String stallId, int bufferSize, int waiterCount,
			String threadName, Long waitMillis, String pinnedFrameSnapshot, Throwable cause) {
		if (!Sentry.isEnabled()) {
			return;
		}
		SentryEvent event = cause != null ? new SentryEvent(cause) : new SentryEvent();
		Message message = new Message();
		message.setMessage("Buffer pool stall: all frames pinned");
		message.setFormatted("Buffer pool stall: all frames pinned");
		event.setMessage(message);
		event.setLevel(level);
		event.setFingerprints(List.of("buffer-pool-stall", phase));
		event.setTag("component", "buffer_pool");
		event.setTag("stall_id", stallId);
		event.setTag("stall_phase", phase);
		event.setTag("buffer_size", Integer.toString(bufferSize));
		event.setTag("waiter_count", Integer.toString(waiterCount));
		event.setTag("thread_name", threadName);
		if (waitMillis != null) {
			event.setTag("wait_ms", Long.toString(waitMillis));
		}
		event.setExtra("pinned_frame_snapshot", pinnedFrameSnapshot);
		Sentry.captureEvent(event);
	}
}
