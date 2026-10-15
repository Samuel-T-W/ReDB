package buffer;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-query frame charges, shared by pins and bounded prefetch reservations.
 * One query runs on one thread, so a thread binding covers every pin path,
 * including BTreeManager, without passing a budget through every operator.
 */
public final class FrameBudget implements AutoCloseable {

	private static final ThreadLocal<FrameBudget> CURRENT = new ThreadLocal<>();
	private final int limit;
	private final int prefetchReserve;
	private int prefetchCharged; // guarded by this; releases can run on loader threads
	private int inFlightPrefetch;
	private boolean closed;
	private final AtomicInteger charged = new AtomicInteger();
	private final AtomicInteger peakCharged = new AtomicInteger();

	public FrameBudget(int limit) {
		this(limit, 0);
	}

	public FrameBudget(int limit, int prefetchReserve) {
		if (limit < 0) throw new IllegalArgumentException("negative frame budget: " + limit);
		if (prefetchReserve < 0 || prefetchReserve > limit)
			throw new IllegalArgumentException("invalid prefetch reserve: " + prefetchReserve);
		this.limit = limit;
		this.prefetchReserve = prefetchReserve;
	}

	public void charge() {
		if (!tryCharge()) throw new FrameBudgetExceededException(limit, charged());
	}

	public boolean tryCharge() {
		for (;;) {
			int count = charged.get();
			if (count == limit) return false;
			if (charged.compareAndSet(count, count + 1)) {
				peakCharged.accumulateAndGet(count + 1, Math::max);
				return true;
			}
		}
	}

	public void release() {
		charged.updateAndGet(count -> {
			if (count == 0) throw new IllegalStateException("frame charge already zero");
			return count - 1;
		});
	}

	// Prefetch shares the total limit but cannot consume the frames sized for BNL pins.
	public synchronized boolean tryChargePrefetch() {
		if (closed || prefetchCharged == prefetchReserve || !tryCharge()) return false;
		prefetchCharged++;
		return true;
	}

	public synchronized void releasePrefetch() {
		if (prefetchCharged == 0) throw new IllegalStateException("prefetch charge already zero");
		release();
		prefetchCharged--;
	}

	synchronized boolean beginPrefetch() {
		if (closed) return false;
		inFlightPrefetch++;
		return true;
	}

	synchronized void finishPrefetch() {
		inFlightPrefetch--;
		notifyAll();
	}

	synchronized boolean isClosed() { return closed; }

	/** Stops new prefetches and drains submitted tasks before query-file cleanup. */
	@Override
	public synchronized void close() {
		closed = true;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (inFlightPrefetch != 0) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) throw new IllegalStateException("Timed out draining prefetch tasks");
			try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted draining prefetch tasks", e);
			}
		}
	}

	public int charged() { return charged.get(); }
	public int peakCharged() { return peakCharged.get(); }
	public int limit() { return limit; }
	public static FrameBudget current() { return CURRENT.get(); }

	/** Installs a budget until close, restoring any enclosing query's binding. */
	public static Scope install(FrameBudget budget) {
		FrameBudget previous = CURRENT.get();
		CURRENT.set(Objects.requireNonNull(budget));
		return () -> {
			if (previous == null) CURRENT.remove();
			else CURRENT.set(previous);
		};
	}

	public interface Scope extends AutoCloseable {
		@Override void close();
	}
}
