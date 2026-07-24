package com.hcimguide;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocationSuppressionStateTest
{
	private static final String STEP_ONE = "E1.B1#1";
	private static final String STEP_TWO = "E1.B1#2";
	private static final StepLocationHint TITHE = new StepLocationHint(
		"Tithe Farm", new WorldPoint(1801, 3505, 0), false, false, false,
		"place:tithe-farm", LocationSource.NAMED_PLACE, LocationConfidence.EXACT, 5, 2);

	@Test
	public void equivalentFollowUpStaysHiddenWhilePlayerRemainsInArea()
	{
		LocationSuppressionState state = new LocationSuppressionState();
		state.hide(STEP_ONE, TITHE, new WorldPoint(1801, 3505, 0), 32);
		assertTrue(state.hides(STEP_ONE, TITHE, 4));
		assertFalse(state.updatePlayer(new WorldPoint(1818, 3505, 0), 32));
		assertTrue(state.hides(STEP_TWO, TITHE.inferredCopy(), 4));
	}

	@Test
	public void leavingMakesFutureReturnEligibleButKeepsOriginStepHidden()
	{
		LocationSuppressionState state = new LocationSuppressionState();
		state.hide(STEP_ONE, TITHE, new WorldPoint(1801, 3505, 0), 32);
		assertTrue(state.updatePlayer(new WorldPoint(1840, 3505, 0), 32));
		assertTrue(state.hides(STEP_ONE, TITHE, 4));
		assertFalse(state.hides(STEP_TWO, TITHE, 4));
	}

	@Test
	public void hidingFarAwayStillHidesOriginButDoesNotCarryToNextStep()
	{
		LocationSuppressionState state = new LocationSuppressionState();
		state.hide(STEP_ONE, TITHE, new WorldPoint(1000, 1000, 0), 32);
		assertTrue(state.hides(STEP_ONE, TITHE, 4));
		assertFalse(state.hides(STEP_TWO, TITHE, 4));
	}

	/**
	 * Pins the 1.5.1 fix: eligibility and the leave check are horizontal
	 * only, so climbing a storey AT the destination (upstairs in a bank,
	 * down into a cellar) is not "leaving" and carryover persists. The
	 * pre-1.5.1 behavior this replaces treated any plane change as leaving.
	 */
	@Test
	public void planeChangeAtTheDestinationDoesNotEndCarryover()
	{
		LocationSuppressionState state = new LocationSuppressionState();
		state.hide(STEP_ONE, TITHE, new WorldPoint(1801, 3505, 0), 32);
		assertFalse(state.updatePlayer(new WorldPoint(1801, 3505, 1), 32));
		assertTrue(state.hides(STEP_ONE, TITHE, 4));
		assertTrue(state.hides(STEP_TWO, TITHE, 4));
	}

	@Test
	public void walkingAwayOnAnotherPlaneStillEndsCarryover()
	{
		LocationSuppressionState state = new LocationSuppressionState();
		state.hide(STEP_ONE, TITHE, new WorldPoint(1801, 3505, 0), 32);
		assertTrue(state.updatePlayer(new WorldPoint(1840, 3505, 1), 32));
		assertTrue(state.hides(STEP_ONE, TITHE, 4));
		assertFalse(state.hides(STEP_TWO, TITHE, 4));
	}
}
