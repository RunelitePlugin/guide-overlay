package com.hcimguide;

import net.runelite.api.Tile;

/**
 * A ground item tile that a current-bank step needs the player to pick up.
 */
public class GroundHighlight
{
	private final Tile tile;
	private final String name;
	private final String normalized;
	private final String singularized;

	public GroundHighlight(Tile tile, String name)
	{
		this.tile = tile;
		this.name = name;
		// precomputed once at spawn so per-tick matching is plain string equality
		this.normalized = Names.normalize(name);
		this.singularized = Names.singularize(name);
	}

	public Tile getTile()
	{
		return tile;
	}

	public String getName()
	{
		return name;
	}

	/** True when this ground item satisfies the given requirement's name. */
	public boolean matches(ItemReq req)
	{
		return normalized.equals(req.getNormalized())
			|| singularized.equals(req.getSingularized());
	}
}
