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

	/** "Waterskin(4)", "Prayer potion(3)": a name with a trailing dose count. */
	private static final java.util.regex.Pattern DOSE_SUFFIX =
		java.util.regex.Pattern.compile("^(.*?[a-z])(\\(\\d+\\))$");

	private static void a(String colloquial, String canonical)
	{
		ALIASES.put(ItemReq.normalize(colloquial), canonical);
	}

	static
	{
		// ---- diary gear / jewelry (charged names differ from bare mentions)


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
			// ---- 1.5.4d: BRUHsailer confirmed items (verified names)
			a("Goldsmith Gauntlets", "Goldsmith gauntlets");
			a("Gold Gauntlets", "Goldsmith gauntlets");
			a("Costume Needle", "Costume needle");
			a("Lead Ore", "Lead ore");
			a("Broad Arrowtip Packs", "Broad arrowhead pack");
			a("Broad Arrowhead Pack", "Broad arrowhead pack");
			a("Energy Potions", "Energy potion(4)");
			a("Chronicle Cards", "Teleport card");
			a("Bucket Packs", "Empty bucket pack");
			a("Broad Arrowtips", "Broad arrowtips");
			a("Trollweiss", "Trollweiss");
			a("Trollweis", "Trollweiss");
			a("Berserker Helmet", "Berserker helm");

			// Deliberately NO bare "Earth"/"Water"/"Mind" -> rune aliases:
			// neither guide ever puts those words in an item position, and a
			// context-free alias would turn a step like "Collect water" into
			// a hard rune requirement that blocks trip-ready. Re-add only
			// with a quantity-context rule if a guide ever needs it.
			a("Earth Staff", "Staff of earth");

			// ---- 1.5.4e: Sailing items (names confirmed from Shipbuilding wiki)
			a("Linen Sails", "Wooden mast and linen sails");
			a("Oak Mast", "Oak mast and linen sails");
			a("Salvaging Station", "Salvaging station (facility)");
			a("Salvaging Hooks", "Mithril salvaging hook");
			a("Mithril Salvaging Hooks", "Mithril salvaging hook");
			a("Kegs", "Keg (facility)");
			a("Keg", "Keg (facility)");

			// ---- 1.5.4f: confirmed quest/Sailing items (verified from wiki)
			a("Crate of Looty", "Crate of looty");
			a("Vile Vigour", "Vile Vigour");

			// ---- 1.8.1: proved unresolved by a live-client audit of both guides
			a("Air Staff", "Staff of air");
			a("Fire Staff", "Staff of fire");

			a("Adrigal", "Ardrigal");
			a("Karamja Rum", "Karamjan rum");
			a("Raw Rat", "Raw rat meat");
			a("Raw Sea Bass", "Raw bass");
			a("Red Eclipse", "Eclipse red");
			a("Kitten", "Pet kitten");

			a("Black Full Helmet", "Black full helm");
			a("Mithril Full Helmet", "Mithril full helm");
			a("Runite Spear", "Rune spear");
			a("Silver Tiara", "Tiara");
			a("Crab Meat", "Crab meat");
			a("Doctor Hat", "Doctor's hat");
			a("Druidic Pouch", "Druid pouch");
			a("Duelling Ring (8)", "Ring of dueling(8)");
			a("Defence (4)", "Defence potion(4)");
			a("Guthix Rest", "Guthix rest(4)");
			a("Elemental Bars", "Elemental metal");
			a("Regular Log", "Logs");
			a("regular Logs", "Logs");
			a("Unlit Candle", "Candle");
			a("Slime", "Bucket of slime");
			a("bait", "Fishing bait");
			a("mindbombs", "Wizard\u0027s mind bomb");
			a("Willow Branches", "Willow branch");
			a("willow branches", "Willow branch");
			a("his bones", "Bones");
			a("NOTED: Ashes", "Ashes");
			a("Remaining Dragon Bones", "Dragon bones");
			a("extra Steel warhammer", "Steel warhammer");
			a("Goutweed with Protect", "Goutweed");
			a("pack of empty buckets", "Empty bucket pack");
			a("Invent of Planks", "Plank");
			a("Butterfly Magic Net", "Butterfly net");
			a("Barcrawl", "Barcrawl card");
			a("Poisoned Sheep feed", "Sheep feed");

			// ---- 1.8.2: real items the second live audit proved need a name hop
			a("bucket packs", "Empty bucket pack");
			a("defence potion", "Defence potion(4)");
			a("more planks", "Plank");
			a("pairs of climbing boots", "Climbing boots");
			a("sack of 10 potatoes", "Potatoes(10)");
			a("second knife", "Knife");
			a("vinegar", "Jug of vinegar");

			// ---- 1.8.8: from the first fully-scanned live audit
			a("Crab Meat", "Crab meat");
			a("Isafdor Painting", "Isafdar painting");
			a("5 Coloured Balls", "Stone ball");
			a("all 5 Coloured Balls", "Stone ball");
			a("Coloured Balls", "Stone ball");
			a("Pink Roses", "Roses");
			a("Red Roses", "Roses");
			a("White Roses", "Roses");






			// ---- 1.9.0: confirmed against the wiki
			a("Battered Key", "Battered key");


			// ---- 1.9.1: the guide names differ from the in-game names
			a("Brimstone boots", "Boots of brimstone");
			a("Masterthief Armband", "Thieves\u0027 armband");
			a("Masterthief armband", "Thieves\u0027 armband");
			// the plain wizard hat IS black in game; only the (g)/(t) variants
			// carry a colour word, so "Black Wizard Hat" means the base item
			a("Black Wizard Hat", "Wizard hat");
			a("Black wizard hat", "Wizard hat");

			// ---- 1.11.0: bare rune words in a list where "Rune" is written once
			a("air", "Air rune");
			a("earth", "Earth rune");
			a("fire", "Fire rune");
			a("water", "Water rune");
			a("mind", "Mind rune");
			a("law", "Law rune");
			a("astral", "Astral rune");
			a("cosmic", "Cosmic rune");
			a("nature", "Nature rune");
			a("chaos", "Chaos rune");
			a("death", "Death rune");
			a("blood", "Blood rune");
			a("soul", "Soul rune");
			a("body", "Body rune");
			a("Tooth", "Ogre tooth");

			// ---- 1.11.4: reported from live in-client checking
			a("Butterfly Magic Net", "Magic butterfly net");
			a("Butterfly net/Magic Net", "Magic butterfly net");
			a("Magic Net", "Magic butterfly net");
			a("Crab Meat", "Giant crab meat");
			a("sand", "Bucket of sand");

			// NOTE: "Food" deliberately has NO alias. Any cooked food satisfies
			// these steps, so it stays free text: the resolver reports free text
			// as UNRESOLVABLE, which is ignored for presence borders and for trip
			// readiness. Aliasing it to a specific fish would show a red missing
			// border to a player carrying a different food. AliasTest guards this.

		// Auto-register the plural of every dose-suffixed canonical name:
		// guides write "Waterskins(4)" but the item is "Waterskin(4)". Doing this
		// by rule rather than by hand covers every potion and dose item at once.
		java.util.Map<String, String> plurals = new HashMap<>();
		for (String canonical : new java.util.HashSet<>(ALIASES.values()))
		{
			java.util.regex.Matcher m = DOSE_SUFFIX.matcher(canonical);
			if (m.matches())
			{
				plurals.put(ItemReq.normalize(m.group(1) + "s" + m.group(2)), canonical);
			}
		}
		ALIASES.putAll(plurals);
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
