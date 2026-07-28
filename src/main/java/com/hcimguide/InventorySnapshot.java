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
		new InventorySnapshot(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

	private final Map<String, Integer> byNormalized;
	private final Map<String, Integer> bySingularized;
	private final Map<ItemCategory, Integer> byCategory;

	public InventorySnapshot(Map<String, Integer> byNormalized, Map<String, Integer> bySingularized)
	{
		this(byNormalized, bySingularized, Collections.emptyMap());
	}

	public InventorySnapshot(Map<String, Integer> byNormalized, Map<String, Integer> bySingularized,
		Map<ItemCategory, Integer> byCategory)
	{
		this.byNormalized = byNormalized;
		this.bySingularized = bySingularized;
		this.byCategory = byCategory;
	}

	/**
	 * Count of items matching the requirement. Alternative groups sum every
	 * eligible item, so 10 sharks + 11 lobsters satisfies 21 any cooked food.
	 */
	public int countOf(ItemReq req)
	{
		if (req.isCategoryRequirement())
		{
			return byCategory.getOrDefault(req.getCategory(), 0);
		}
		int total = 0;
		// dedupe on BOTH key forms: two options that normalize differently
		// but singularize the same ("Egg"/"Eggs") must count the held stack
		// once, whichever lookup path each of them takes. This also covers the
		// alias candidates now returned by getMatchNames: a guide name and its
		// canonical name resolve to the same held stack and must count once.
		// Dedupe on the SINGULAR CANDIDATE SET, not on the name.
		//
		// "Willow Branches" and its alias "Willow branch" are two names for one
		// held stack. Marking only the exact normalized form let the second name
		// match again through the exact path, so one branch satisfied a
		// requirement for two: a false completion, which is worse than the
		// missing-item bug aliases were added to fix. Any overlap in candidate
		// forms now means the stack has already been counted.
		java.util.Set<String> counted = new java.util.HashSet<>();
		for (String name : req.getMatchNames())
		{
			java.util.Set<String> candidates = Names.singularCandidates(name);
			boolean alreadyCounted = false;
			for (String candidate : candidates)
			{
				if (counted.contains(candidate))
				{
					alreadyCounted = true;
					break;
				}
			}
			if (alreadyCounted)
			{
				continue;
			}

			Integer exact = byNormalized.get(Names.normalize(name));
			if (exact != null)
			{
				total += exact;
				counted.addAll(candidates);
				continue;
			}
			for (String candidate : candidates)
			{
				Integer loose = bySingularized.get(candidate);
				if (loose != null)
				{
					total += loose;
					counted.addAll(candidates);
					break;
				}
			}
		}
		return total;
	}
}
