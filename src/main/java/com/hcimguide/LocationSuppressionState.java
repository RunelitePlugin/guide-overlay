package com.hcimguide;

import net.runelite.api.coords.WorldPoint;

/**
 * Destination-scoped location-guide suppression.
 *
 * The origin step always stays hidden until the user changes steps. Equivalent
 * follow-up steps inherit that suppression only when the player was near the
 * hidden destination and has not actually left it. This prevents a Tithe Farm
 * arrow from reappearing across consecutive local instructions while allowing
 * a later return to Tithe Farm to guide normally after the player leaves.
 */
final class LocationSuppressionState
{
	private String identity;
	private WorldPoint point;
	private String originStepKey;
	private boolean carryEligible;

	synchronized void hide(String stepKey, StepLocationHint hint, WorldPoint player, int leaveRadius)
	{
		if (stepKey == null || hint == null || hint.getPoint() == null)
		{
			return;
		}
		identity = hint.getIdentity();
		point = hint.getPoint();
		originStepKey = stepKey;
		// Eligibility ignores the plane on purpose: a player standing on top of
		// the destination but upstairs in a bank, or below it in a dungeon, is
		// still AT the place. Requiring a plane match here disabled carryover
		// for every multi-storey destination. The plane IS checked when the
		// player leaves (see updatePlayer), so a genuine departure still ends
		// carryover.
		carryEligible = isWithinRadius(player, point, leaveRadius);
	}

	synchronized void clear()
	{
		identity = null;
		point = null;
		originStepKey = null;
		carryEligible = false;
	}

	synchronized boolean isActive()
	{
		return identity != null && point != null && originStepKey != null;
	}

	synchronized boolean hides(String stepKey, StepLocationHint hint, int equivalentRadius)
	{
		if (!isActive() || stepKey == null || hint == null)
		{
			return false;
		}
		// The user's explicit hide action applies to the whole origin step even
		// when they hid a long route while still far from the destination.
		if (originStepKey.equals(stepKey))
		{
			return true;
		}
		if (!carryEligible)
		{
			return false;
		}
		if (identity.equals(hint.getIdentity()))
		{
			return true;
		}
		// DELIBERATE ASYMMETRY with hide()/updatePlayer(), which are
		// horizontal-only: equivalence here still requires the same plane.
		// A different-identity destination on another storey is treated as a
		// different place and un-hides; same-identity follow-ups (the common
		// inherited-context case) match above regardless of plane.
		StepLocationHint hidden = StepLocationHint.manual(identity,
			"Suppressed destination", point, Math.max(1, equivalentRadius));
		return hidden.equivalentTo(hint, equivalentRadius);
	}

	/**
	 * Records that the player has left the hidden area. The origin step remains
	 * hidden, but suppression will no longer carry into a later equivalent step.
	 * Returns true when carryover eligibility changed.
	 */
	synchronized boolean updatePlayer(WorldPoint player, int leaveRadius)
	{
		if (!isActive() || !carryEligible || player == null)
		{
			return false;
		}
		if (!isWithinRadius(player, point, leaveRadius))
		{
			carryEligible = false;
			return true;
		}
		return false;
	}

	/**
	 * Horizontal proximity only. Used for both carryover eligibility and the
	 * leave check so that climbing a staircase at the destination does not read
	 * as having left it; only moving away in x/y does.
	 */
	private static boolean isWithinRadius(WorldPoint player, WorldPoint target, int radius)
	{
		return player != null && target != null
			&& tileDistance(player, target) <= Math.max(1, radius);
	}

	private static int tileDistance(WorldPoint a, WorldPoint b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
	}
}
