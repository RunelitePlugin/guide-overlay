package com.hcimguide;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable snapshot of the player's inventory, pre-keyed by normalized and
 * singularized item names so that presence checks are O(1) map lookups
 * instead of per-comparison regex work (they run on the EDT for every grid
 * slot on every inventory change).
 */
public class InventorySnapshot
{
	public static final InventorySnapshot EMPTY =
		new InventorySnapshot(Collections.emptyMap(), Collections.emptyMap());

	private final Map<String, Integer> byNormalized;
	private final Map<String, Integer> bySingularized;

	public InventorySnapshot(Map<String, Integer> byNormalized, Map<String, Integer> bySingularized)
	{
		this.byNormalized = byNormalized;
		this.bySingularized = bySingularized;
	}

	/** Count of items matching the requirement (exact normalized name, plural-tolerant fallback). */
	public int countOf(ItemReq req)
	{
		Integer exact = byNormalized.get(req.getNormalized());
		if (exact != null)
		{
			return exact;
		}
		Integer loose = bySingularized.get(req.getSingularized());
		return loose != null ? loose : 0;
	}
}
