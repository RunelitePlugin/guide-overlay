package com.hcimguide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static com.hcimguide.TeleportOption.Category.JEWELRY;
import static com.hcimguide.TeleportOption.Category.OTHER;
import static com.hcimguide.TeleportOption.Category.SPELL;
import static com.hcimguide.TeleportOption.Category.TAB;
import static com.hcimguide.TeleportOption.item;
import static com.hcimguide.TeleportOption.jewelry;
import static com.hcimguide.TeleportOption.rune;

/**
 * The curated teleport table the route suggester draws from - standard
 * spellbook teleports, their tablet equivalents, and the common jewelry
 * teleports, focused on what early/mid-game guides actually use.
 *
 * MAINTENANCE: to add a teleport, add one entry here (destination
 * coordinates are surface world coordinates; item names are lowercase
 * prefixes of the in-game names). Nothing else needs changing - config
 * category toggles, bank checks, and the exclude list pick it up
 * automatically. Level/quest/spellbook requirements are deliberately NOT
 * modeled: the suggester only proposes options whose ITEMS you own, and a
 * suggestion you can't cast yet is easy to ignore (or exclude by name in
 * the config). Charge tracking beyond "has a charged variant" isn't
 * modeled either - the Chronicle (whose charges aren't visible in its
 * name) may occasionally be suggested while empty.
 */
final class TeleportDirectory
{
	static final List<TeleportOption> ALL;

	static
	{
		List<TeleportOption> t = new ArrayList<>();

		// ---------------------------------------------------------- spells
		// (free; 30-minute cooldown not modeled - it's the fallback anyway)
		t.add(new TeleportOption("Lumbridge Home Teleport", SPELL, 3222, 3218, 0));
		t.add(new TeleportOption("Varrock Teleport", SPELL, 3212, 3424, 0,
			rune("law rune", 1), rune("air rune", 3), rune("fire rune", 1)));
		t.add(new TeleportOption("Lumbridge Teleport", SPELL, 3222, 3218, 0,
			rune("law rune", 1), rune("air rune", 3), rune("earth rune", 1)));
		t.add(new TeleportOption("Falador Teleport", SPELL, 2964, 3378, 0,
			rune("law rune", 1), rune("air rune", 3), rune("water rune", 1)));
		t.add(new TeleportOption("Camelot Teleport", SPELL, 2757, 3479, 0,
			rune("law rune", 1), rune("air rune", 5)));
		t.add(new TeleportOption("Ardougne Teleport", SPELL, 2661, 3300, 0,
			rune("law rune", 2), rune("water rune", 2)));
		t.add(new TeleportOption("Kourend Castle Teleport", SPELL, 1643, 3673, 0,
			rune("law rune", 2), rune("soul rune", 2), rune("water rune", 4)));

		// ---------------------------------------------------------- tablets
		t.add(new TeleportOption("Varrock teleport tab", TAB, 3212, 3424, 0, item("varrock teleport")));
		t.add(new TeleportOption("Lumbridge teleport tab", TAB, 3222, 3218, 0, item("lumbridge teleport")));
		t.add(new TeleportOption("Falador teleport tab", TAB, 2964, 3378, 0, item("falador teleport")));
		t.add(new TeleportOption("Camelot teleport tab", TAB, 2757, 3479, 0, item("camelot teleport")));
		t.add(new TeleportOption("Ardougne teleport tab", TAB, 2661, 3300, 0, item("ardougne teleport")));

		// ---------------------------------------------------------- jewelry
		t.add(new TeleportOption("Amulet of glory → Edgeville", JEWELRY, 3087, 3496, 0, jewelry("amulet of glory")));
		t.add(new TeleportOption("Amulet of glory → Karamja", JEWELRY, 2918, 3176, 0, jewelry("amulet of glory")));
		t.add(new TeleportOption("Amulet of glory → Draynor Village", JEWELRY, 3105, 3251, 0, jewelry("amulet of glory")));
		t.add(new TeleportOption("Amulet of glory → Al Kharid", JEWELRY, 3293, 3163, 0, jewelry("amulet of glory")));
		t.add(new TeleportOption("Ring of dueling → Emir's Arena", JEWELRY, 3316, 3235, 0, jewelry("ring of dueling")));
		t.add(new TeleportOption("Ring of dueling → Ferox Enclave", JEWELRY, 3151, 3635, 0, jewelry("ring of dueling")));
		t.add(new TeleportOption("Ring of dueling → Castle Wars", JEWELRY, 2440, 3089, 0, jewelry("ring of dueling")));
		t.add(new TeleportOption("Games necklace → Burthorpe", JEWELRY, 2898, 3553, 0, jewelry("games necklace")));
		t.add(new TeleportOption("Games necklace → Barbarian Outpost", JEWELRY, 2520, 3571, 0, jewelry("games necklace")));
		t.add(new TeleportOption("Games necklace → Wintertodt Camp", JEWELRY, 1624, 3938, 0, jewelry("games necklace")));
		t.add(new TeleportOption("Necklace of passage → Wizards' Tower", JEWELRY, 3113, 3179, 0, jewelry("necklace of passage")));
		t.add(new TeleportOption("Necklace of passage → The Outpost", JEWELRY, 2430, 3348, 0, jewelry("necklace of passage")));
		t.add(new TeleportOption("Combat bracelet → Warriors' Guild", JEWELRY, 2882, 3548, 0, jewelry("combat bracelet")));
		t.add(new TeleportOption("Combat bracelet → Champions' Guild", JEWELRY, 3191, 3363, 0, jewelry("combat bracelet")));
		t.add(new TeleportOption("Combat bracelet → Edgeville Monastery", JEWELRY, 3052, 3488, 0, jewelry("combat bracelet")));
		t.add(new TeleportOption("Skills necklace → Fishing Guild", JEWELRY, 2611, 3390, 0, jewelry("skills necklace")));
		t.add(new TeleportOption("Skills necklace → Crafting Guild", JEWELRY, 2933, 3295, 0, jewelry("skills necklace")));

		// ---------------------------------------------------------- other
		t.add(new TeleportOption("Ectophial → Port Phasmatys", OTHER, 3660, 3524, 0, item("ectophial")));
		t.add(new TeleportOption("Chronicle → Champions' Guild", OTHER, 3200, 3355, 0, item("chronicle")));

		ALL = Collections.unmodifiableList(t);
	}

	/** Every distinct item-name prefix the directory references (for the stock tracker). */
	static Set<String> allNeedParts()
	{
		Set<String> parts = new LinkedHashSet<>();
		for (TeleportOption option : ALL)
		{
			for (TeleportOption.ItemNeed need : option.getNeeds())
			{
				parts.add(need.getNamePart());
			}
		}
		return parts;
	}

	private TeleportDirectory()
	{
	}
}
