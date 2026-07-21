package com.hcimguide;

import java.util.HashMap;
import java.util.Map;

/**
 * Colloquial guide name -&gt; EXACT in-game item name, compiled from the real
 * guide's item lists ("Ardy Cloak" -&gt; "Ardougne cloak 1", "Wine" -&gt; "Jug of
 * wine"). Mapping to canonical NAMES rather than hardcoded ids keeps this
 * table maintainable and lets the normal resolution pipeline (known ids,
 * price search, full-database scan) find the id - so a typo here degrades
 * to "no icon", never to a wrong item.
 *
 * Keys are normalized (lowercase alphanumeric) at build time via
 * {@link ItemReq#normalize}, so lookup tolerates spacing/case/punctuation.
 * Covers: colloquial nicknames, dose/charge-suffixed jewelry and potions
 * referenced bare, guide typos, and mid-word plurals ("Balls of Wool")
 * that suffix-based singularization can't reach.
 */
final class ItemAliases
{
	private static final Map<String, String> ALIASES = new HashMap<>();

	private static void a(String colloquial, String canonical)
	{
		ALIASES.put(ItemReq.normalize(colloquial), canonical);
	}

	static
	{
		// ---- diary gear / jewelry (charged names differ from bare mentions)
		a("Ardy Cloak", "Ardougne cloak 1");
		a("Ardougne Cloak", "Ardougne cloak 1");
		a("Dueling Ring", "Ring of dueling(8)");
		a("Duelling Ring", "Ring of dueling(8)");
		a("Ring of Duelling", "Ring of dueling(8)");
		a("Duel Ring", "Ring of dueling(8)");
		a("Games Necklace", "Games necklace(8)");
		a("Necklace of Passage", "Necklace of passage(5)");
		a("Digsite Pendant", "Digsite pendant (5)");
		a("Amulet of Glory", "Amulet of glory(4)");
		a("Glory", "Amulet of glory(4)");
		a("Recoil Rings", "Ring of recoil");
		a("Rings of Recoil", "Ring of recoil");
		a("Karamja Gloves", "Karamja gloves 1");
		a("Rada's Blessing", "Rada's blessing 1");
		a("Kourend Blessing 1", "Rada's blessing 1");
		a("Kharedst Memoirs", "Kharedst's memoirs");
		a("Amulet of Catspeak", "Catspeak amulet");
		a("Teleport Crystal", "Teleport crystal (1)");
		a("Elf Teleport Crystal", "Teleport crystal (1)");

		// ---- teleport tablets / house
		a("House Teleport", "Teleport to house");
		a("House Teleport Tablet", "Teleport to house");
		a("House Tab", "Teleport to house");
		a("Falador Teleport Tablet", "Falador teleport");

		// ---- potions referenced without doses
		a("Prayer Potion", "Prayer potion(4)");
		a("Prayer Potions", "Prayer potion(4)");
		a("Antipoison", "Antipoison(4)");
		a("Antipoisons", "Antipoison(4)");
		a("Super Antipoison", "Superantipoison(4)");
		a("Restore Potion", "Restore potion(4)");
		a("Restores Potions", "Restore potion(4)");
		a("Super Restores", "Super restore(4)");
		a("Stamina Potions", "Stamina potion(4)");
		a("Combat Potions", "Combat potion(4)");
		a("Guam Potion", "Guam potion (unf)");
		a("Harralander Potion", "Harralander potion (unf)");
		a("Tarromin Potion", "Tarromin potion (unf)");
		a("Ranarr Potion", "Ranarr potion (unf)");
		a("Wizard Mind Bomb", "Wizard's mind bomb");
		a("Waterskin", "Waterskin(4)");
		a("Waterskins", "Waterskin(4)");

		// ---- food and drink
		a("Wine", "Jug of wine");
		a("Wines", "Jug of wine");
		a("Milk", "Bucket of milk");
		a("Karambwan", "Cooked karambwan");
		a("Karambwans", "Cooked karambwan");
		a("Karambawns", "Cooked karambwan");
		a("Karamwbwans", "Cooked karambwan");
		a("Karambwanji", "Raw karambwanji");
		a("Cooked Trout", "Trout");
		a("Dwarven Cake", "Dwarven rock cake");
		a("Sack of Potatoes", "Potatoes(10)");

		// ---- currency
		a("Gold", "Coins");
		a("1Mil Coins", "Coins");
		a("gp", "Coins");
		a("Gold Pieces", "Coins");

		// ---- BRUHsailer shorthands
		a("POH Tab", "Teleport to house");
		a("POH Tabs", "Teleport to house");

		// ---- bare rune shorthands
		a("Air", "Air rune");
		a("Law", "Law rune");
		a("Astral", "Astral rune");

		// ---- containers referenced empty
		a("Empty Jug", "Jug");
		a("Empty Bowl", "Bowl");
		a("Empty Bucket", "Bucket");
		a("Empty Buckets", "Bucket");
		a("Empty Pot", "Pot");
		a("Empty Pots", "Pot");

		// ---- mid-word plurals suffix-singularization can't reach
		a("Balls of Wool", "Ball of wool");
		a("Buckets of Milk", "Bucket of milk");
		a("Buckets of Water", "Bucket of water");
		a("Buckets of Slime", "Bucket of slime");
		a("Bolts of Cloth", "Bolt of cloth");
		a("Bronze Knives", "Bronze knife");
		a("Marks of Grace", "Mark of grace");

		// ---- equipment nicknames
		a("Bone Crossbow", "Dorgeshuun crossbow");
		a("Dorg Bow", "Dorgeshuun crossbow");
		a("Chaps", "Leather chaps");
		a("Monk Robes", "Monk's robe");
		a("Desert Robes", "Desert shirt");
		a("Desert Robe Bottom", "Desert robe");
		a("Blackjack", "Willow blackjack");
		a("Magic Net", "Magic butterfly net");
		a("Antifire Shield", "Anti-dragon shield");
		a("Antidragon fire Shield", "Anti-dragon shield");
		a("Holy Sickle", "Silver sickle (b)");
		a("Nails", "Steel nails");
		a("Bolts", "Bronze bolts");

		// ---- herbs / ingredients / misc
		a("Guam Leaves", "Guam leaf");
		a("Doogle Leaf", "Doogle leaves");
		a("Woad Leaves", "Woad leaf");
		a("Red Spider Eggs", "Red spiders' eggs");
		a("Jangerberry", "Jangerberries");
		a("Redberry", "Redberries");
		a("Cadavaberry", "Cadava berries");
		a("Tar", "Swamp tar");
		a("Paste", "Swamp paste");
		a("Pestle", "Pestle and mortar");
		a("Mortar", "Pestle and mortar");
		a("Cut Dragonstone", "Dragonstone");
		a("Goat Horn", "Desert goat horn");
		a("Cannonball Mould", "Ammo mould");
		a("Clockwork Mechanism", "Clockwork");
		a("Cat", "Pet cat");

		// ---- guide typos (kept working even if the wiki fixes them)
		a("Chroncle", "Chronicle");
		a("Tinerbox", "Tinderbox");
		a("Pure Essenece", "Pure essence");
		a("Marrentil", "Marrentill");
		a("Seceteaurs", "Secateurs");
		a("Bow of Fardhinen", "Bow of faerdhinen");
		a("Barrel of Naptha", "Barrel of naphtha");
		a("Khardian Headpiece", "Kharidian headpiece");
		a("Dual Macahuitls", "Dual macuahuitl");
		a("Lantern Lense", "Lantern lens");
		a("Nulodion Notes", "Nulodion's notes");
		a("Tobans stolen Gold", "Toban's gold");
		a("t'd Crunchies", "Toad crunchies");
	}

	/**
	 * @param normalizedName an {@link ItemReq#normalize}d guide name
	 * @return the exact in-game item name, or null when no alias exists
	 */
	static String canonical(String normalizedName)
	{
		return ALIASES.get(normalizedName);
	}

	private ItemAliases()
	{
	}
}
