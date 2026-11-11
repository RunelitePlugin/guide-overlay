package com.hcimguide;

import net.runelite.api.coords.WorldPoint;

/** Debounces waypoint arrival so passing near a destination for one tick cannot skip it. */
final class WaypointArrivalTracker
{
	private int ticksInside;
	private String token;
	/**
	 * Step whose arrival is disarmed until the player is seen OUTSIDE its
	 * active waypoint. Standing still inside the radius is a level, not an
	 * edge: without this a step reopened where it completed is "arrived"
	 * again two ticks later. Deliberately survives {@link #reset()}, which
	 * runs on every tick the step is not being tracked.
	 */
	private String leaveFirstKey;

	void reset()
	{
		ticksInside = 0;
		token = null;
	}

	/**
	 * Require the player to leave the step's active waypoint before it can
	 * count as arrived again. Pass null to drop any pending requirement.
	 */
	void requireLeaveBeforeArrival(String stepKey)
	{
		leaveFirstKey = stepKey;
	}

	boolean update(String stepKey, int index, StepLocationHint waypoint, WorldPoint player)
	{
		if (stepKey == null || waypoint == null || waypoint.getPoint() == null || player == null)
		{
			reset();
			return false;
		}
		String current = stepKey + "|" + index + "|" + waypoint.getIdentity();
		if (!current.equals(token))
		{
			token = current;
			ticksInside = 0;
		}
		WorldPoint target = waypoint.getPoint();
		if (leaveFirstKey != null && !leaveFirstKey.equals(stepKey))
		{
			leaveFirstKey = null; // guidance moved on - the requirement is moot
		}
		if (player.getPlane() != target.getPlane() || tileDistance(player, target) > waypoint.getArrivalRadius())
		{
			ticksInside = 0;
			leaveFirstKey = null; // seen outside: the next entry is a fresh arrival
			return false;
		}
		if (leaveFirstKey != null)
		{
			ticksInside = 0;
			return false;
		}
		if (++ticksInside >= waypoint.getConfirmationTicks())
		{
			reset();
			return true;
		}
		return false;
	}

	private static int tileDistance(WorldPoint a, WorldPoint b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
	}
}
