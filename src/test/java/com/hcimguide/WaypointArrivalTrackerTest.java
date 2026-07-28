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
}
