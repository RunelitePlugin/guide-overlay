package com.hcimguide;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WaypointArrivalTrackerTest
{
	private static final StepLocationHint WAYPOINT = new StepLocationHint(
		"Destination", new WorldPoint(3200, 3200, 0), false, false, false,
		"place:destination", LocationSource.NAMED_PLACE, LocationConfidence.EXACT, 4, 2);

	@Test
	public void requiresTwoTicksInsideArrivalRadius()
	{
		WaypointArrivalTracker tracker = new WaypointArrivalTracker();
		assertFalse(tracker.update("step", 0, WAYPOINT, new WorldPoint(3200, 3200, 0)));
		assertTrue(tracker.update("step", 0, WAYPOINT, new WorldPoint(3200, 3200, 0)));
	}

	@Test
	public void passingNearForOneTickDoesNotAdvance()
	{
		WaypointArrivalTracker tracker = new WaypointArrivalTracker();
		assertFalse(tracker.update("step", 0, WAYPOINT, new WorldPoint(3201, 3200, 0)));
		assertFalse(tracker.update("step", 0, WAYPOINT, new WorldPoint(3210, 3200, 0)));
		assertFalse(tracker.update("step", 0, WAYPOINT, new WorldPoint(3201, 3200, 0)));
	}

	@Test
	public void wrongPlaneNeverAdvances()
	{
		WaypointArrivalTracker tracker = new WaypointArrivalTracker();
		assertFalse(tracker.update("step", 0, WAYPOINT, new WorldPoint(3200, 3200, 1)));
		assertFalse(tracker.update("step", 0, WAYPOINT, new WorldPoint(3200, 3200, 1)));
	}

	@Test
	public void aReopenedStepDoesNotArriveAgainWhileThePlayerStandsStill()
	{
		WorldPoint inside = new WorldPoint(3201, 3200, 0);
		WaypointArrivalTracker tracker = new WaypointArrivalTracker();
		assertFalse(tracker.update("step", 0, WAYPOINT, inside));
		assertTrue(tracker.update("step", 0, WAYPOINT, inside));

		// the step is reopened (previous-step) with the player still there
		tracker.reset();
		tracker.requireLeaveBeforeArrival("step");
		for (int tick = 0; tick < 10; tick++)
		{
			assertFalse(tracker.update("step", 0, WAYPOINT, inside));
			tracker.reset(); // per-tick resets elsewhere must not re-arm it
		}
	}

	@Test
	public void leavingAndReturningIsAFreshArrival()
	{
		WorldPoint inside = new WorldPoint(3201, 3200, 0);
		WaypointArrivalTracker tracker = new WaypointArrivalTracker();
		tracker.requireLeaveBeforeArrival("step");
		assertFalse(tracker.update("step", 0, WAYPOINT, inside));
		assertFalse(tracker.update("step", 0, WAYPOINT, new WorldPoint(3210, 3200, 0)));
		assertFalse(tracker.update("step", 0, WAYPOINT, inside));
		assertTrue(tracker.update("step", 0, WAYPOINT, inside));
	}

	@Test
	public void theLeaveRequirementDoesNotCarryOverToAnotherStep()
	{
		WorldPoint inside = new WorldPoint(3201, 3200, 0);
		WaypointArrivalTracker tracker = new WaypointArrivalTracker();
		tracker.requireLeaveBeforeArrival("rewound");
		assertFalse(tracker.update("other", 0, WAYPOINT, inside));
		assertTrue(tracker.update("other", 0, WAYPOINT, inside));
		// ...and it was dropped, not parked, when guidance moved on
		assertFalse(tracker.update("rewound", 0, WAYPOINT, inside));
		assertTrue(tracker.update("rewound", 0, WAYPOINT, inside));
	}
}
