package buffer;

/**
 * Telemetry callbacks for buffer-pool eviction stalls (every frame pinned).
 *
 * <p>
 * Implementations MUST be non-blocking. {@link BufferManager} invokes these
 * while holding its pool lock, so I/O here would stall every pin/unpin. Async
 * enqueue (for example Sentry captureEvent) is safe; blocking I/O is not.
 */
public interface BufferPoolReporter {

	BufferPoolReporter NOOP = new BufferPoolReporter() {
		@Override
		public void onStallStarted(String stallId, int bufferSize, int waiterCount, String threadName,
				String pinnedFrameSnapshot) {
		}

		@Override
		public void onStallResolved(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
				String pinnedFrameSnapshot) {
		}

		@Override
		public void onStallTimeout(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
				String pinnedFrameSnapshot) {
		}

		@Override
		public void onStallFailed(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
				String pinnedFrameSnapshot, Throwable cause) {
		}
	};

	void onStallStarted(String stallId, int bufferSize, int waiterCount, String threadName, String pinnedFrameSnapshot);

	void onStallResolved(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
			String pinnedFrameSnapshot);

	void onStallTimeout(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
			String pinnedFrameSnapshot);

	void onStallFailed(String stallId, int bufferSize, int waiterCount, String threadName, long waitMillis,
			String pinnedFrameSnapshot, Throwable cause);
}
