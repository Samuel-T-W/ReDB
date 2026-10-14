package buffer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-query frame charges, shared by pins and future prefetch reservations.
 * One query runs on one thread, so a thread binding covers every pin path,
 * including BTreeManager, without passing a budget through every operator.
 */
public final class FrameBudget {

	private static final ThreadLocal<FrameBudget> CURRENT = new ThreadLocal<>();
	private final int limit;
	private final AtomicInteger charged = new AtomicInteger();
	private final AtomicInteger peakCharged = new AtomicInteger();

	public FrameBudget(int limit) {
		if (limit < 0) throw new IllegalArgumentException("negative frame budget: " + limit);
		this.limit = limit;
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
