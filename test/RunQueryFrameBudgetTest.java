import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Working-frame reservation for BNL block sizing.
 *
 * <p>{@code N = (budget - WORKING_FRAMES) / 2} is per query. Subtracting one
 * leftover frame from the whole pool would share that frame across every
 * concurrent inner scan.
 */
public class RunQueryFrameBudgetTest {

	@Test
	public void minBudgetYieldsOnePageBlocks() {
		assertEquals(2 + RunQuery.WORKING_FRAMES, RunQuery.MIN_FRAME_BUDGET);
		assertEquals(1, RunQuery.blockPagesPerJoin(RunQuery.MIN_FRAME_BUDGET));
	}

	@Test
	public void typicalBudgetYieldsFourPageBlocks() {
		assertEquals(4, RunQuery.blockPagesPerJoin(9));
	}

	@Test
	public void leftoverFramesCoverWorkingSetForEveryValidBudget() {
		for (int budget = RunQuery.MIN_FRAME_BUDGET; budget <= 64; budget++) {
			int blockPages = RunQuery.blockPagesPerJoin(budget);
			assertTrue(blockPages >= 1, "budget " + budget + " must yield a BNL block");
			assertTrue(
					budget - 2 * blockPages >= RunQuery.WORKING_FRAMES,
					"budget " + budget
							+ " must keep a per-query working frame after both BNL blocks");
		}
	}

	@Test
	public void rejectsBudgetBelowMinimum() {
		IllegalArgumentException thrown = assertThrows(
				IllegalArgumentException.class,
				() -> RunQuery.blockPagesPerJoin(RunQuery.MIN_FRAME_BUDGET - 1));
		assertTrue(thrown.getMessage().contains(String.valueOf(RunQuery.MIN_FRAME_BUDGET)));
	}
}
