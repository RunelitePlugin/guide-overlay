package com.hcimguide;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Guide phrases that name a CONCEPT rather than a specific item: "Combat Gear",
 * "Range Gear", "Melee". No item id can represent these, so they behave
 * differently from a normal requirement everywhere it matters:
 *
 * <ul>
 *   <li>they draw a client sprite instead of an item icon,</li>
 *   <li>they are never inventory-checked, so they cannot show a red "missing"
 *       border,</li>
 *   <li>they never block trip readiness, which previously stalled forever
 *       because the name could not resolve to an item id.</li>
 * </ul>
 *
 * <p>This class deliberately has no RuneLite dependency so the parser and the
 * offline harness can both consult it. {@code ItemIconResolver} maps these same
 * phrases to a sprite.</p>
 */
final class ConceptItems
{
	/** Normalized concept phrases. */
	private static final Set<String> PHRASES = new HashSet<>();

	static
	{
		for (String s : Arrays.asList(
			// Only phrases with NO possible item list remain concepts. Anything
			// naming a family (pickaxe, axe, boots, food) is now an ItemCategory,
			// and anything naming one item is an alias or a step override.
			"combat gear",
			"combat gear melee",
			"combat gear for slagilith",
			"magic gear",
			"mage gear",
			"range gear",
			"ranged gear",
			"range combat gear",
			"bone crossbow gear",
			"melee gear",
			"gear",
			"your gear",
			"best gear",
			"combat equipment",
			"armour",
			"armor",
			"melee",
			"ranged",
			"magic",
			"air spells runes",
			"crumble undead runes",
			"death combat runes",
			"blast spells",
			"magic; wizard mind bomb",
			"combat runes",
			"bomb",
			// no single item exists for these
			"noted rune from corrupted gauntlet", "rune from corrupted gauntlet"))
		{
			PHRASES.add(Names.normalize(s));
		}
	}

	/** True when {@code name} names a concept rather than a concrete item. */
	static boolean isConcept(String name)
	{
		return name != null && PHRASES.contains(Names.normalize(name));
	}

	private ConceptItems()
	{
	}
}
