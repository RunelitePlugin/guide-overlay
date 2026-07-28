package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocationSuppressionStateTest
{
	private static final String STEP_ONE = "E1.B1#1";
	private static final String STEP_TWO = "E1.B1#2";

	@Test
	public void hideAppliesToExactlyTheOriginStep()
	{
		LocationSuppressionState state = new LocationSuppressionState();
		state.hide(STEP_ONE);
		assertTrue(state.hides(STEP_ONE));
		// an equivalent follow-up destination is a DIFFERENT step and must
		// not inherit the hide - this pins the step-scoped contract
		assertFalse(state.hides(STEP_TWO));
	}

	@Test
	public void guidedStepMovingOnClearsTheSuppression()
	{
		LocationSuppressionState state = new LocationSuppressionState();
		state.hide(STEP_ONE);
		assertFalse("origin still guided: no change",
			state.clearWhenGuidedStepChanged(STEP_ONE));
		assertTrue(state.hides(STEP_ONE));
		assertTrue("guided step changed: suppression clears",
			state.clearWhenGuidedStepChanged(STEP_TWO));
		assertFalse("returning to the origin does not restore the old hide",
			state.hides(STEP_ONE));
		assertFalse(state.isActive());
	}

	@Test
	public void clearAndNullsAreSafe()
	{
		LocationSuppressionState state = new LocationSuppressionState();
		assertFalse(state.hides(null));
		assertFalse(state.clearWhenGuidedStepChanged(null));
		state.hide(null);
		assertFalse(state.isActive());
		state.hide(STEP_ONE);
		state.clear();
		assertFalse(state.isActive());
		assertFalse(state.hides(STEP_ONE));
	}

	@Test
	public void hideWithNoGuidedStepClearsWhenAnotherStepBecomesGuided()
	{
		LocationSuppressionState state = new LocationSuppressionState();
		state.hide(STEP_ONE);
		// guided step becomes null (e.g. bank completed) -> origin no longer
		// guided -> suppression clears rather than lingering for a return
		assertTrue(state.clearWhenGuidedStepChanged(null));
		assertFalse(state.isActive());
	}
}
