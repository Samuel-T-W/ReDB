package buffer;

public final class FrameBudgetExceededException extends IllegalStateException {
	public FrameBudgetExceededException(int limit, int charged) {
		super("Frame budget exceeded: limit=" + limit + ", charged=" + charged);
	}
}
