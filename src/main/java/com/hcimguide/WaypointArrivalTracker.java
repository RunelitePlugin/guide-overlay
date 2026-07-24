package com.hcimguide;

import net.runelite.api.coords.WorldPoint;

/** Debounces waypoint arrival so passing near a destination for one tick cannot skip it. */
final class WaypointArrivalTracker
{
	private int ticksInside;
	private String token;

	void reset()
	{
		ticksInside = 0;
		token = null;
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
		if (player.getPlane() != target.getPlane() || tileDistance(player, target) > waypoint.getArrivalRadius())
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
