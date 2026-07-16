package com.hcimguide;

import java.util.List;

/**
 * Picks the fastest way to the current objective: walk, or one of the
 * teleports the player actually owns the items for. Distances are
 * straight-line (Chebyshev) tile estimates - this is a SUGGESTION engine,
 * not a pathfinder; the optional Shortest Path plugin hand-off draws the
 * real tile path. Pure Java (ints only) so it is unit-testable offline.
 */
final class RouteSuggester
{
	/** Fixed cost (in tile-equivalents) of casting/rubbing a carried teleport. */
	static final int CARRIED_COST = 25;

	/** Extra cost when the items are only in the bank (detour to withdraw). */
	static final int BANKED_COST = 90;

	/**
	 * A teleport must beat plain walking by at least this many tiles before
	 * it's suggested - nobody wants "teleport" spam for a 40-tile stroll.
	 */
	static final int MIN_SAVING = 30;

	/** A teleport option plus where its items currently are. */
	static class Candidate
	{
		final TeleportOption option;
		final boolean banked;

		Candidate(TeleportOption option, boolean banked)
		{
			this.option = option;
			this.banked = banked;
		}
	}

	/** The winning option, or null when walking is (close enough to) fastest. */
	static class Suggestion
	{
		final TeleportOption option;
		final boolean banked;
		final int cost;
		final int walkCost;

		Suggestion(TeleportOption option, boolean banked, int cost, int walkCost)
		{
			this.option = option;
			this.banked = banked;
			this.cost = cost;
			this.walkCost = walkCost;
		}
	}

	/**
	 * @param px,py player position; tx,ty objective position (same plane
	 *              assumed - callers pass surface coordinates)
	 */
	static Suggestion best(int px, int py, int tx, int ty, List<Candidate> candidates)
	{
		final int walk = dist(px, py, tx, ty);
		Suggestion best = null;
		for (Candidate c : candidates)
		{
			if (c == null || c.option == null)
			{
				continue;
			}
			int cost = dist(c.option.getX(), c.option.getY(), tx, ty)
				+ (c.banked ? BANKED_COST : CARRIED_COST);
			if (cost <= walk - MIN_SAVING && (best == null || cost < best.cost))
			{
				best = new Suggestion(c.option, c.banked, cost, walk);
			}
		}
		return best;
	}

	/** Chebyshev distance - OSRS movement is 8-directional. */
	static int dist(int ax, int ay, int bx, int by)
	{
		return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
	}

	private RouteSuggester()
	{
	}
}
