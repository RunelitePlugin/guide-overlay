package com.hcimguide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact item lists for individual guide steps whose wording the general parser
 * cannot resolve correctly.
 *
 * <p>Keyed on the NORMALIZED step text rather than a bank number, because bank
 * numbers shift whenever a bank is inserted earlier in the guide while the
 * sentence itself stays put. The bank number in each comment is for auditing
 * only. Normalization strips wiki markup, case, and punctuation, so a cosmetic
 * wiki edit does not silently drop the override.</p>
 *
 * <p>An override that stops matching degrades to normal parsing - never to a
 * wrong item - so a stale entry costs accuracy, not correctness. Keep this
 * table small: anything expressible as a general rule belongs in
 * {@link ItemListParser} or {@link ItemAliases} instead.</p>
 */
final class StepItemOverrides
{
	private static final Map<String, List<ItemReq>> OVERRIDES = new HashMap<>();

	private static void o(String stepText, ItemReq... items)
	{
		OVERRIDES.put(key(stepText), Collections.unmodifiableList(Arrays.asList(items)));
	}

	private static ItemReq q(String name, int qty)
	{
		return new ItemReq(name, qty);
	}

	private static ItemReq label(String name, int qty, String display)
	{
		return new ItemReq(name, qty, display);
	}

	private static ItemReq any(String displayName, int minimumTotal, String quantityLabel, String... options)
	{
		return ItemReq.anyOf(displayName, minimumTotal, quantityLabel, options);
	}

	private static ItemReq cookedFood(int minimumTotal, String label)
	{
		return ItemReq.category(ItemCategory.COOKED_FOOD, minimumTotal, label);
	}


	static
	{
		// ---- Bank 36: rune shop purchase. Comma-grouped thousands plus a "100+"
		// open quantity; the general parser now handles both, but the trailing

		// ---- Bank 36: the soul rune purchase is its own step
		o("Buy 1 Soul Rune [Legend' Quest]", q("Soul rune", 1));

		// ---- Bank 30-ish: moulds sold as bare nouns needing the "mould" suffix,
		// followed by two non-mould items after the ampersand
		o("Buy moulds: Ring, Amulet, 2x Necklace, Bracelet, Sickle, Tiara, 3x Needle & 50 Thread (from [[Rommik]] in Rimmington)",
			q("Ring mould", 1),
			q("Amulet mould", 1),
			q("Necklace mould", 2),
			q("Bracelet mould", 1),
			q("Sickle mould", 1),
			q("Tiara mould", 1),
			q("Needle", 3),
			q("Thread", 50));

		// ---- Research notes and the pickaxe are alternatives, not a set: the
		// slash separates a needed item from an explicit negation
		o("Withdraw: Coins, Research Notes (2 inventory Slots) / Bronze pickaxe not needed",
			q("Coins", 1),
			q("Research notes", 1));


		// ---- armour sets named as a single phrase (all names verified against
		// the live item list; "Crystal chestplate"/"Initiate helmet" do NOT exist)
		o("Withdraw: Rogue Outfit, Few food, Seedbox, Garden Pie",
			q("Rogue mask", 1), q("Rogue top", 1), q("Rogue trousers", 1),
			q("Rogue gloves", 1), q("Rogue boots", 1),
			cookedFood(1, "few"),
			q("Seed box", 1), q("Garden pie", 1));

		// ---- spell rune sets, each taken from the spell's own wiki infobox
		o("Crumble undead runes", q("Air rune", 2), q("Earth rune", 2), q("Chaos rune", 1));
		o("Humidify Runes", q("Fire rune", 1), q("Water rune", 3), q("Astral rune", 1));
		o("Cure Me Spells", q("Astral rune", 2), q("Cosmic rune", 2), q("Law rune", 1));
		o("Curse Runes", q("Earth rune", 3), q("Water rune", 2), q("Body rune", 1));
		o("Teleport Runes to House", q("Air rune", 1), q("Earth rune", 1), q("Law rune", 1));
		o("Varrock Teleport Runes", q("Air rune", 3), q("Fire rune", 1), q("Law rune", 1));

		// case 3 (guide line 427)
		o("Loot & Bank 139 [[Plank]]s (1x 27 Inventory & 4x 28)",
			q("Plank", 139));
		// case 56 (guide line 163)
		o("Collect the Logs & fletch 2,100 Arrowshafts (hop between 2 worlds)",
			q("Arrow shaft", 2100));
		// case 57 (guide line 164)
		o("Collect 11 Logs & make 10 into Planks. Make the 3 Waxwood Planks.",
			q("Logs", 11),
			q("Plank", 10));
		// case 58 (guide line 362)
		o("Buy atleast 1x Bronze Bars [Mournings End Pt 2]",
			q("Bronze bar", 1));
		// case 61 (guide line 847)
		o("Buy your 100 Soda Ash/Sand if you haven't yet",
			q("Soda ash", 100),
			q("Bucket of sand", 100));
		// case 65 (guide line 975)
		o("Buy at least 4x Jute seeds, 1x Marigold seeds, 6x Onion seeds, 6x Cabbage seeds for [Kandarin Easy Diary and Garden of Tranquility]",
			q("Jute seed", 4),
			q("Marigold seed", 1),
			q("Onion seed", 6),
			q("Cabbage seed", 6));
		// case 68 (guide line 1224)
		o("Take out your supercompost from the [[Tool Leprechaun]] if you've stored it there",
			q("Supercompost", 1));
		// case 69 (guide line 1327)
		o("Buy Bronze, Iron, and Steel Knives before & after each completion [Temple of Ikov]",
			q("Bronze knife", 1),
			q("Iron knife", 1),
			q("Steel knife", 1));
		// case 70 (guide line 1328)
		o("Buy 25 more Lockpicks after you get the outfit",
			q("Lockpick", 25));
		// case 76 (guide line 2189)
		o("Withdraw: Coins, Dueling Ring, a knife, log or axe, ardy cloak, dramen staff.",
			q("Coins", 1),
			q("Ring of dueling(8)", 1),
			q("Knife", 1),
			any("Log or axe", 1, null,
				"Logs", "Bronze axe", "Iron axe", "Steel axe", "Black axe",
				"Mithril axe", "Adamant axe", "Rune axe", "Dragon axe"),
			q("Ardougne cloak 1", 1),
			q("Dramen staff", 1));
		// case 80 (guide line 3582)
		o("Withdraw a Chisel & Uncut gems from Gauntlet.",
			q("Chisel", 1),
			any("Uncut gems", 1, "all",
				"Uncut sapphire", "Uncut emerald", "Uncut ruby",
				"Uncut diamond", "Uncut dragonstone"));
		// case 81 (guide line 3642)
		o("Withdraw: Bow of Faerdhinen & Crystal Armour, Seal of Passage, 3 lots of 7 random cooked food",
			q("Bow of Faerdhinen", 1),
			q("Crystal helm", 1),
			q("Crystal body", 1),
			q("Crystal legs", 1),
			q("Seal of Passage", 1),
			cookedFood(21, "3×7"));

		// Guide wording, not a parser fault: "Raw" is written once and is meant to
		// carry across the whole list. Nothing in the sentence marks the later
		// items as raw, so only a per-step override can express the intent.
		o("Buy Raw Sea Bass, Swordfish, 3x Cod & 15 Sardines while here for your Kitten",
			q("Raw bass", 1),
			q("Raw swordfish", 1),
			q("Raw cod", 3),
			q("Raw sardine", 15));

		// Guide punctuation, not a parser fault: "6 Clay Hammer" is a missing
		// comma between two separate items.
		o("Withdraw: Teleport Runes, Orange Dye, Blue Dye, Spicy Maggots, Charcoal, Dyed Orange, Soggy Bread, 2 Iron Ore, Blurite Ore, 4x Copper Ore, 6 Clay Hammer & antique Lamp (26 Inventory Slots)",
			q("Law rune", 1), q("Orange dye", 1), q("Blue dye", 1),
			q("Spicy maggots", 1), q("Charcoal", 1), q("Soggy bread", 1),
			q("Iron ore", 2), q("Blurite ore", 1), q("Copper ore", 4),
			q("Clay", 6), q("Hammer", 1), q("Antique lamp", 1));

		// "If 56/57/58 Magic" is a level condition, not an item. The bomb is
		// carried regardless.
		o("Withdraw: Teleport Runes, Coins, Death Runes, Antipoison, Super Antipoison, Scrying Orb If 56/57/58 Magic; Wizard Mind Bomb, (9/10 Inventory Slots) + Food",
			q("Law rune", 1), q("Coins", 1), q("Death rune", 1),
			q("Antipoison(4)", 1), q("Superantipoison(4)", 1),
			q("Scrying orb", 1), q("Wizard\u0027s mind bomb", 1));

        // Trailing noun written once and meant to carry backwards. Handled as
        // overrides rather than a general rule: "Rune Axe, Guam, Pestle &
        // Mortar, 15 Swamp Tar" has the same shape but Guam is a herb, not
        // "Guam Tar", so a blanket rule would corrupt it.
		o("Buy 100 mind, 100 air, 50 water, 50 earth and 20 fire runes (no rune packs).",
			q("Mind rune", 100), q("Air rune", 100), q("Water rune", 50),
			q("Earth rune", 50), q("Fire rune", 20));

		// "Buy Black Full Helmet & Plate legs": the colour is written once and
		// applies to both pieces. Nothing in the sentence marks the second.
		o("Buy Black Full Helmet & Plate legs [Heroes Quest/Kings Ransom]",
			q("Black full helm", 1), q("Black platelegs", 1));

		// Mastering Mixology: "goggles" and "Amulet" name the two Mixology
		// rewards, not generic equipment.
		o("Buy the goggles/Amulet if you haven\u2019t already.",
			q("Prescription goggles", 1), q("Alchemist\u0027s amulet", 1));

		// "Teleport Runes to House, Falador, Varrock, Ardougne" is ONE phrase
		// naming four destinations; the destinations were being read as separate
		// items and "Ardougne" was picked up as a diary flag.
		o("Withdraw: Coins, Teleport Runes to House, Falador, Varrock, Ardougne, Nature Runes, Noted Rune from Corrupted Gauntlet, 4x Steel Bars, Plank Sack, Inventory of Teak Planks",
			q("Coins", 1),
			q("Law rune", 4),
			q("Air rune", 12),
			q("Earth rune", 1),
			q("Fire rune", 1),
			q("Water rune", 1),
			q("Nature rune", 1),
			q("Noted Rune from Corrupted Gauntlet", 1),
			q("Steel bar", 4),
			q("Plank sack", 1),
			q("Teak plank", 26));

		// ---- two distinct pickups in one line; "when getting" hides the second
		o("Take 2x Logs when getting the rat tail",
			q("Logs", 2),
			q("Rat's tail", 1));
	}

	/** Normalized lookup key: wiki markup stripped, lowercase alphanumeric. */
	static String key(String stepText)
	{
		if (stepText == null)
		{
			return "";
		}
		String s = stepText;
		int link;
		while ((link = s.indexOf("[[")) >= 0)
		{
			int close = s.indexOf("]]", link);
			if (close < 0)
			{
				break;
			}
			String inner = s.substring(link + 2, close);
			int pipe = inner.lastIndexOf('|');
			s = s.substring(0, link) + (pipe >= 0 ? inner.substring(pipe + 1) : inner)
				+ s.substring(close + 2);
		}
		return Names.normalize(s);
	}

	/**
	 * @return a defensive copy of the override list, or null when this step has
	 *     none and normal parsing should run
	 */
	static List<ItemReq> forStep(String stepText)
	{
		List<ItemReq> hit = OVERRIDES.get(key(stepText));
		return hit == null ? null : new ArrayList<>(hit);
	}

	private StepItemOverrides()
	{
	}
}
