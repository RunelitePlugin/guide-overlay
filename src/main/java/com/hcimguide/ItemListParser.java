package com.hcimguide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts item requirement lists from step text.
 *
 * "Withdraw: Coins, Air Runes, Bread (8 Inventory slots)" -&gt; [Coins, Air Runes, Bread]
 * "Collect 3x Logs [Tree Gnome Village]"                  -&gt; [3x Logs]
 */
public final class ItemListParser
{
	private static final Pattern WITHDRAW = Pattern.compile("(?i)^withdraw:?\\s+(.*)$");
	private static final Pattern GATHER_VERB = Pattern.compile("(?i)^(?:collect|take|buy|loot|pick up)\\s+(?:the\\s+|a\\s+|an\\s+)?(.*)$");
	/**
	 * Leading quantity. The 'x' separator may be glued straight onto the name
	 * ("6xAir Runes") as well as spaced ("6x Air Runes" / "6 Air Runes"), so
	 * whitespace after the x is optional when the x is present.
	 */
	private static final Pattern QTY_PREFIX = Pattern.compile(
		"(?i)^(\\d+)\\s*(\\+?)\\s*(?:x\\s*|\\s+)(.*)$");

	/**
	 * Comma-grouped thousands ("1,200 Nature Runes"). Collapsed BEFORE the list
	 * split, because the split treats that comma as an item separator and would
	 * otherwise yield an item literally named "1" followed by "200 Nature
	 * Runes". A digit on both sides is required so a real separator
	 * ("Coins, 2 Law") is never joined.
	 */
	/**
	 * Dialogue-option tails: "(1,1)", "(2,3)", "(1,4,2)". The digits are chat
	 * menu choices, not quantities. Stripped BEFORE the list split, otherwise
	 * the inner comma splits and leaves an item literally named "1)".
	 */
	private static final Pattern DIALOGUE_OPTIONS = Pattern.compile(
		"\\(\\s*\\d+(?:\\s*,\\s*\\d+)+\\s*\\)");

	private static final Pattern GROUPED_THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d{3})");

	/** "Full Inventory of X" / "2x Inventory of X" / "2 Inventories of X". */
	private static final Pattern INVENTORY_OF = Pattern.compile(
		"(?i)^(?:(\\d+)\\s*x?\\s+|full\\s+|an?\\s+)?inventor(?:y|ies)\\s+of\\s+(.*)$");

	/** "1 extra Rotten Apple" - the guide means one MORE than the step already needs. */
	private static final Pattern EXTRA_QTY = Pattern.compile(
		"(?i)^(\\d+)\\s*x?\\s+extra\\s+(.*)$");

	/** "60 Noted Empty Buckets" - noted form, quantity kept. */
	private static final Pattern NOTED = Pattern.compile("(?i)^noted\\s+(.+)$");

	/** Open-ended guide quantity: "All Water Runes". */
	private static final Pattern ALL_QTY = Pattern.compile("(?i)^all\\s+(.+)$");

	/**
	 * Vague quantities the guide uses instead of a number: "Few food", "some
	 * food", "lots of food", "as many bones as possible". The word itself is
	 * kept as the icon label so the player sees the guide's own wording rather
	 * than an invented count.
	 */
	private static final Pattern VAGUE_QTY = Pattern.compile(
		"(?i)^(few|a few|some|lots of|plenty of|several|multiple|many|as many)\\s+(.+?)"
		+ "(?:\\s+as possible)?$");

	/**
	 * "Best Air Spell" / "Best Earth Spell": the guide means the strongest
	 * elemental strike the player can cast, which for item purposes is just the
	 * matching elemental rune. "best food" is the food stand-in. The "best"
	 * qualifier is stripped and the noun resolved normally.
	 */
	private static final Pattern BEST_SPELL = Pattern.compile(
		"(?i)^best\\s+(air|earth|fire|water)\\s+spell$");

	/** "3 lots of 7 random cooked food" - trailing filler before the noun. */
	private static final Pattern RANDOM_FILLER = Pattern.compile(
		"(?i)\\b(?:random|assorted|various|misc(?:ellaneous)?)\\s+");

	/**
	 * A trailing parenthetical qualifier on an item ("Potion (3)/(4)",
	 * "Runes (29,000/14,500 per Onyx)"). Its interior slashes and commas are
	 * NOT list separators, so it is stripped before the item is split. Dose
	 * markers like "(4)" that are part of the real item name are preserved by
	 * running this only when the parenthesis contains a slash, space, or word.
	 */
	private static final Pattern TRAILING_QUALIFIER = Pattern.compile(
		"\\s*\\((?=[^)]*[/ a-zA-Z,])[^)]*\\)\\s*(?:\\[[^\\]]*\\])?\\s*$");

	/** Explicit negation inside a withdraw list ("Bronze pickaxe not needed"). */
	private static final Pattern NEGATED = Pattern.compile(
		"(?i)\\b(?:not needed|no longer needed|not required|if you have|optional)\\b");

	/**
	 * Travel methods and colloquialisms that read as gatherable nouns after
	 * "Take"/"Use" but are transport or scenery, never inventory items. Steps
	 * mentioning these are already coloured as transport by
	 * {@link StepTextSemantic}; this stops them ALSO producing a bogus item.
	 */
	private static final Set<String> NOT_ITEMS_TRANSPORT = new HashSet<>(Arrays.asList(
		"minecart", "mine cart", "cart", "carpet", "magic carpet", "spirit tree",
		"boat", "ship", "canoe", "glider", "gnome glider", "quetzal", "charter ship",
		"fairy ring", "shortcut", "portal", "ferry", "raft", "barge",
		"edgeville fairy ring", "balloon", "zip line", "ziplink", "gangplank"));

	/**
	 * Instruction and prose fragments that survive the splitters but name no
	 * item. Each was confirmed against the real guide text rather than guessed:
	 * they are verbs ("bank it", "bury"), locations, or advice.
	 */
	/** Orphaned closing bracket left when a quest tag is split mid-way. */
	private static final Pattern ORPHAN_BRACKET = Pattern.compile("^[^\\[(]*[\\])]$");

	private static final Set<String> NOT_ITEMS_PROSE = new HashSet<>(Arrays.asList(
		"bank", "bank it", "bank them", "bank all", "bury", "bury them",
		"deposit", "deposit all", "rebank", "teleport out", "training",
		"head", "etc", "nothing", "anything you think is useful",
		"your items", "after each completion", "herb runs", "seaweed runs",
		"thralls", "tick manipulation", "easy ca's", "easy cas",
		"as possible", "or knife", "or axe", "or cat", "second option",
		"the rest", "remainder", "if needed", "if required", "optional",
		"but only", "it's possible", "its possible", "you may need to buy more",
		"per onyx", "don't die here", "dont die here",
		"produce items", "medium kourend", "kebos diary", "western provinces easy",
		"varrock easy", "fairytale items", "move your poh", "boosting", "arteglass",
		"skiff", "your skiff", "sloop", "build two kegs",
		"vigroy cart system", "shilo village cart system", "agility shortcut",
		"balloon back", "cart back", "train cart", "rope swing",
		"medium diaries", "medium diary rewards", "produce", "sacks",
		"vinegar or wine", "combat stats", "your combat stats",
		// ---- 1.8.2: prose the second live-client audit proved are not items
		"10",
		"alch them",
		"along with your trollweiss",
		"are",
		"awowogei up",
		"banking",
		"blow the whistle",
		"but",
		"buying the ore",
		"charting xp bonuses",
		"client hopping",
		"crack a wall safe",
		"delivery order",
		"do",
		"dwellberries work",
		"e",
		"earth spell",
		"enchantment points",
		"even",
		"get",
		"grabbing",
		"graveyard points",
		"ideally",
		"inv of staves",
		"lamping herblore",
		"leaving 1250k",
		"minutes",
		"misc",
		"mourning’s end part ii",
		"moving-over-distance sphere",
		"of either of them",
		"only starts growing",
		"out a knife",
		"parrot",
		"port bounty tasks",
		"premade choc bombs legs",
		"progress family crest",
		"put them",
		"putting the lamp",
		"putting the lamps",
		"runes",
		"sand is approximately 88",
		"secret entrance",
		"stagger",
		"stop with chaos",
		"swap your servant",
		"telekinetic points",
		"they’re",
		"thieving",
		"train",
		"unlock bones",
		"upgraded device",
		"using the icon",
		"using them",
		"visit the museum",
		"whichever comes last",
		"white pear",
		// ---- 1.8.1: proved not to be items by the live-client audit
		"biohazard items",
		"blood moon",
		"boaty",
		"cat out of ardy",
		"depositing items",
		"extra beer",
		"falador",
		"fletch as you move",
		"forth",
		"get told",
		"hand",
		"mincart",
		"more logs",
		"mushtree",
		"north quetzal",
		"refill compost bin",
		"rune from corrupted gauntlet",
		"save 60% favour",
		"steel",
		"the first map piece",
		"varrock",
		// ---- 1.7.8: names the full two-guide audit proved are not items
		"arceuus minecart",
		"at varrock east bank: plague sample",
		"boat back",
		"build a house",
		"build an iron helm",
		"buy",
		"buy a big net",
		"buy a ticket",
		"buy black full helm",
		"buy herb sack",
		"charter boat",
		"collect",
		"collect the xp lamp",
		"collect two rock cakes",
		"combat gear to kill kalphite queen",
		"combine the three map pieces [dragon slayer]",
		"complete death",
		"complete tale of the righteous",
		"complete the forsaken tower",
		"complete the quest",
		"continue",
		"continue client of kourend",
		"continue eadgar?s ruse",
		"continue hand",
		"continue holy grail",
		"continue swan song",
		"cook the bass",
		"cosmics per world",
		"curse runes if 2 tick",
		"cut a cactus one trip banking at nardah [desert easy diary]",
		"decant into 4 dose potion",
		"do all of rfd up",
		"each sand is approximately 88",
		"easy",
		"fairy ring biq",
		"fairy ring clr",
		"fairy ring cls",
		"fill one up downstairs",
		"fish two raw cods",
		"glarial?s pebble along the way",
		"just drop the shapes",
		"kill 1 wyrm",
		"kill a deathwing",
		"kill the weaponsmaster",
		"light it with a tinderbox",
		"light source",
		"magic gear to kill giant roc",
		"make",
		"make 7 bronze wires",
		"make all into headless arrows",
		"make two bowls of water",
		"meet up with the scout",
		"mine a gold ore",
		"northern yanille shortcut",
		"oak mast and linen sails",
		"palm leaf drop 5x",
		"pick it up 5x",
		"potato cactus -> 6 karamwbwans",
		"quest cape",
		"range gear to kill sea snake",
		"remainder of cash from blast furnace",
		"repair kits",
		"sand with superglass make runes in the rune pouch",
		"scrying orb if 56 bomb",
		"shortcut back",
		"speak with the bartender",
		"speak with the high priest",
		"spin a strip of cloth",
		"start",
		"start black knights? fortress",
		"start fairytale i",
		"start the grand tree",
		"start the knight?s sword",
		"take",
		"take the boat",
		"take them",
		"the fish",
		"to a total of 10k karambwans",
		"top up your death rune stack",
		"vanilla pod along the way",
		"varrock hard diary reward",
		"water",
		"weapon to bop random stuff",
		"world",
		"yanille shortcut"));

	/**
	 * Real item names that contain the word "and". Splitting a list on "and" is
	 * correct for guide prose ("eight redberries and one banana") but destroys
	 * these ("Pestle and mortar" became two items named Pestle and mortar). The
	 * name's own " and " is masked with a sentinel before the split and restored
	 * afterwards, so the item survives while list separators still work.
	 */
	private static final String[] AND_ITEM_NAMES = {
		"Bow and arrow",
		"Cap and goggles",
		"Egg and tomato",
		"Fishbowl and net",
		"History and hearsay",
		"Hoop and stick",
		"Logs and kindling",
		"Lutwidge and the moonfly",
		"Oak and steel cage",
		"Oak mast and linen sails",
		"Pestle and mortar",
		"Pump and drain",
		"Pump and tub",
		"Tuna and corn",
		"Will and testament",
		"Wooden mast and linen sails"
	};

	/**
	 * Hedges and possessives the guide writes before a count ("Buy your 100
	 * Soda Ash", "Buy at least 4x Jute seeds"). Left in place they became part
	 * of the item name and blocked the quantity prefix behind them.
	 */
	private static final Pattern LEADING_HEDGE = Pattern.compile(
		"(?i)^(?:at\\s+least|at\\s+minimum|atleast|your|my|our|the)\\s+");

	/**
	 * Bare guide phrases that should accept ANY dose or charge. The first entry
	 * is the display name; all entries are acceptable matches.
	 */
	private static final Map<String, String[]> DOSE_GROUPS = new HashMap<>();

	/** Guide phrases naming an item family rather than one exact item. */
	private static final Map<String, ItemCategory> FAMILY_PHRASES = new HashMap<>();

	static
	{
		family("pickaxe", ItemCategory.PICKAXE);
		family("axe", ItemCategory.AXE);
		family("boots", ItemCategory.BOOTS);
		family("any ring", ItemCategory.RING);
		family("ring", ItemCategory.RING);
		family("grimy herb", ItemCategory.GRIMY_HERB);
		family("food", ItemCategory.COOKED_FOOD);
		family("any food", ItemCategory.COOKED_FOOD);
		family("any cooked food", ItemCategory.COOKED_FOOD);
		family("food for elvarg", ItemCategory.COOKED_FOOD);
		family("pies", ItemCategory.COOKED_FOOD);
	}

	private static void family(String phrase, ItemCategory category)
	{
		FAMILY_PHRASES.put(Names.normalize(phrase), category);
	}

	static
	{
		// Ardougne cloak tiers: the diary reward improves with each tier and a
		// higher cloak does everything a lower one does, so a step asking for
		// cloak 1 is satisfied by 1, 2, 3 or 4. Only ONE alias existed before,
		// pinning every mention to cloak 1, so anyone holding a 2/3/4 counted
		// as having nothing.
		dose("ardy cloak", "Ardougne cloak 1", "Ardougne cloak 2", "Ardougne cloak 3", "Ardougne cloak 4");
		dose("ardougne cloak", "Ardougne cloak 1", "Ardougne cloak 2", "Ardougne cloak 3", "Ardougne cloak 4");
		dose("ardy cloak 1", "Ardougne cloak 1", "Ardougne cloak 2", "Ardougne cloak 3", "Ardougne cloak 4");
		dose("ardougne cloak 1", "Ardougne cloak 1", "Ardougne cloak 2", "Ardougne cloak 3", "Ardougne cloak 4");
		dose("ardy cloak 2", "Ardougne cloak 2", "Ardougne cloak 3", "Ardougne cloak 4");
		dose("ardougne cloak 2", "Ardougne cloak 2", "Ardougne cloak 3", "Ardougne cloak 4");
		dose("ardy cloak 3", "Ardougne cloak 3", "Ardougne cloak 4");
		dose("ardougne cloak 3", "Ardougne cloak 3", "Ardougne cloak 4");
		dose("ardy cloak 4", "Ardougne cloak 4");
		dose("ardougne cloak 4", "Ardougne cloak 4");
		// same tier hierarchy as the Ardougne cloak: a higher diary reward does
		// everything a lower one does
		dose("karamja gloves", "Karamja gloves 1", "Karamja gloves 2", "Karamja gloves 3", "Karamja gloves 4");
		dose("karamja gloves 1", "Karamja gloves 1", "Karamja gloves 2", "Karamja gloves 3", "Karamja gloves 4");
		dose("karamja gloves 2", "Karamja gloves 2", "Karamja gloves 3", "Karamja gloves 4");
		dose("karamja gloves 3", "Karamja gloves 3", "Karamja gloves 4");
		dose("karamja gloves 4", "Karamja gloves 4");
		dose("rada\u0027s blessing", "Rada\u0027s blessing 1", "Rada\u0027s blessing 2", "Rada\u0027s blessing 3", "Rada\u0027s blessing 4");
		dose("rada\u0027s blessing 1", "Rada\u0027s blessing 1", "Rada\u0027s blessing 2", "Rada\u0027s blessing 3", "Rada\u0027s blessing 4");
		dose("rada\u0027s blessing 2", "Rada\u0027s blessing 2", "Rada\u0027s blessing 3", "Rada\u0027s blessing 4");
		dose("rada\u0027s blessing 3", "Rada\u0027s blessing 3", "Rada\u0027s blessing 4");
		dose("rada\u0027s blessing 4", "Rada\u0027s blessing 4");
		dose("prayer potion", "Prayer potion(4)", "Prayer potion(3)", "Prayer potion(2)", "Prayer potion(1)");
		dose("prayer potions", "Prayer potion(4)", "Prayer potion(3)", "Prayer potion(2)", "Prayer potion(1)");
		dose("antipoison", "Antipoison(4)", "Antipoison(3)", "Antipoison(2)", "Antipoison(1)");
		dose("antipoisons", "Antipoison(4)", "Antipoison(3)", "Antipoison(2)", "Antipoison(1)");
		dose("super antipoison", "Superantipoison(4)", "Superantipoison(3)", "Superantipoison(2)", "Superantipoison(1)");
		dose("restore potion", "Restore potion(4)", "Restore potion(3)", "Restore potion(2)", "Restore potion(1)");
		dose("restores potions", "Restore potion(4)", "Restore potion(3)", "Restore potion(2)", "Restore potion(1)");
		dose("super restores", "Super restore(4)", "Super restore(3)", "Super restore(2)", "Super restore(1)");
		dose("combat potions", "Combat potion(4)", "Combat potion(3)", "Combat potion(2)", "Combat potion(1)");
		dose("defence potion", "Defence potion(4)", "Defence potion(3)", "Defence potion(2)", "Defence potion(1)");
		dose("energy potions", "Energy potion(4)", "Energy potion(3)", "Energy potion(2)", "Energy potion(1)");
		dose("stamina potions", "Stamina potion(4)", "Stamina potion(3)", "Stamina potion(2)", "Stamina potion(1)");
		dose("guthix rest", "Guthix rest(4)", "Guthix rest(3)", "Guthix rest(2)", "Guthix rest(1)");
		dose("waterskin", "Waterskin(4)", "Waterskin(3)", "Waterskin(2)", "Waterskin(1)");
		dose("waterskins", "Waterskin(4)", "Waterskin(3)", "Waterskin(2)", "Waterskin(1)");
		dose("amulet of glory", "Amulet of glory(4)", "Amulet of glory(3)", "Amulet of glory(2)", "Amulet of glory(1)", "Amulet of glory(6)");
		dose("glory", "Amulet of glory(4)", "Amulet of glory(3)", "Amulet of glory(2)", "Amulet of glory(1)", "Amulet of glory(6)");
		dose("games necklace", "Games necklace(8)", "Games necklace(7)", "Games necklace(6)", "Games necklace(5)", "Games necklace(4)", "Games necklace(3)", "Games necklace(2)", "Games necklace(1)");
		dose("dueling ring", "Ring of dueling(8)", "Ring of dueling(7)", "Ring of dueling(6)", "Ring of dueling(5)", "Ring of dueling(4)", "Ring of dueling(3)", "Ring of dueling(2)", "Ring of dueling(1)");
		dose("duelling ring", "Ring of dueling(8)", "Ring of dueling(7)", "Ring of dueling(6)", "Ring of dueling(5)", "Ring of dueling(4)", "Ring of dueling(3)", "Ring of dueling(2)", "Ring of dueling(1)");
		dose("duel ring", "Ring of dueling(8)", "Ring of dueling(7)", "Ring of dueling(6)", "Ring of dueling(5)", "Ring of dueling(4)", "Ring of dueling(3)", "Ring of dueling(2)", "Ring of dueling(1)");
		dose("ring of duelling", "Ring of dueling(8)", "Ring of dueling(7)", "Ring of dueling(6)", "Ring of dueling(5)", "Ring of dueling(4)", "Ring of dueling(3)", "Ring of dueling(2)", "Ring of dueling(1)");
		dose("necklace of passage", "Necklace of passage(5)", "Necklace of passage(4)", "Necklace of passage(3)", "Necklace of passage(2)", "Necklace of passage(1)");
		dose("digsite pendant", "Digsite pendant (5)", "Digsite pendant (4)", "Digsite pendant (3)", "Digsite pendant (2)", "Digsite pendant (1)");
		dose("teleport crystal", "Teleport crystal (1)", "Teleport crystal (2)", "Teleport crystal (3)", "Teleport crystal (4)", "Teleport crystal (5)");
		dose("elf teleport crystal", "Teleport crystal (1)", "Teleport crystal (2)", "Teleport crystal (3)", "Teleport crystal (4)", "Teleport crystal (5)");
	}

	private static void dose(String phrase, String... options)
	{
		DOSE_GROUPS.put(Names.normalize(phrase), options);
	}

	/** Sentinel standing in for a protected " and " during the list split. */
	private static final String AND_GUARD = "\u0001";

	private static final Pattern AND_ITEM_PATTERN = Pattern.compile(
		"(?i)\\b(?:" + joinAlternation(AND_ITEM_NAMES) + ")\\b");

	private static String joinAlternation(String[] names)
	{
		StringBuilder sb = new StringBuilder();
		for (String n : names)
		{
			if (sb.length() > 0)
			{
				sb.append('|');
			}
			sb.append(Pattern.quote(n));
		}
		return sb.toString();
	}

	/**
	 * Bare modifiers that can precede a shared noun in a list
	 * ("Bronze, Iron, and Steel Knives"). Deliberately narrow: a general rule
	 * would rewrite "Coins, Spade, Dramen Staff" into nonsense.
	 */
	private static final Set<String> LIST_MODIFIERS = new HashSet<>(Arrays.asList(
		"bronze", "iron", "steel", "mithril", "adamant", "adamantite", "rune",
		"runite", "dragon", "silver", "gold", "black", "white", "leather",
		"hard", "studded", "green", "blue", "red", "yellow", "purple"));

	/**
	 * Rewrites "Bronze, Iron, Steel Knives" into three complete names by giving
	 * every bare modifier the noun carried by the final entry. Returns null when
	 * the list is not that shape, which is the common case.
	 */
	private static String[] distributeTrailingNoun(String[] segments)
	{
		if (segments.length < 2)
		{
			return null;
		}
		// find the end of the leading run of bare modifiers: everything before
		// it must be a lone modifier, and the run must close with
		// "<modifier> <noun>". Trailing prose after that is left untouched,
		// which is what real guide lines look like:
		// "Bronze, Iron, and Steel Knives before & after each completion"
		for (int k = 1; k < segments.length; k++)
		{
			boolean leadingAllModifiers = true;
			for (int i = 0; i < k; i++)
			{
				String seg = segments[i].trim();
				if (seg.isEmpty())
				{
					// ", and " leaves an empty segment between separators
					continue;
				}
				if (seg.indexOf(' ') >= 0
					|| !LIST_MODIFIERS.contains(seg.toLowerCase(java.util.Locale.ROOT)))
				{
					leadingAllModifiers = false;
					break;
				}
			}
			if (!leadingAllModifiers)
			{
				return null;
			}
			String candidate = GATHER_CUTOFF.matcher(
				GATHER_BOUNDARY.split(segments[k].trim())[0].trim()).replaceAll("").trim();
			int sp = candidate.lastIndexOf(' ');
			if (sp <= 0)
			{
				continue;
			}
			String noun = candidate.substring(sp + 1).trim();
			String modifier = candidate.substring(0, sp).trim();
			if (noun.isEmpty() || modifier.indexOf(' ') >= 0
				|| !LIST_MODIFIERS.contains(modifier.toLowerCase(java.util.Locale.ROOT)))
			{
				continue;
			}
			String[] out = segments.clone();
			for (int i = 0; i < k; i++)
			{
				String seg = segments[i].trim();
				out[i] = seg.isEmpty() ? seg : seg + " " + noun;
			}
			out[k] = candidate;
			return out;
		}
		return null;
	}

	/** Mask the " and " inside any known item name so the list split skips it. */
	private static String protectAndNames(String text)
	{
		if (text == null)
		{
			return null;
		}
		// guides write both "Pestle and mortar" and "Pestle & Mortar"; the
		// protection list is written with the word, so normalise the ampersand
		// form of a protected phrase before matching. Without this, "Pestle &
		// Mortar" split into two items named Pestle and Mortar.
		if (text.indexOf('&') >= 0)
		{
			for (String phrase : AND_ITEM_NAMES)
			{
				String ampersand = phrase.replace(" and ", " & ");
				int at = indexOfIgnoreCase(text, ampersand);
				while (at >= 0)
				{
					text = text.substring(0, at) + phrase
						+ text.substring(at + ampersand.length());
					at = indexOfIgnoreCase(text, ampersand);
				}
			}
		}
		if (text.indexOf(" and ") < 0)
		{
			return text;
		}
		Matcher m = AND_ITEM_PATTERN.matcher(text);
		StringBuffer sb = new StringBuffer();
		while (m.find())
		{
			m.appendReplacement(sb,
				Matcher.quoteReplacement(m.group().replace(" and ", AND_GUARD)));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static int indexOfIgnoreCase(String haystack, String needle)
	{
		return haystack.toLowerCase(java.util.Locale.ROOT)
			.indexOf(needle.toLowerCase(java.util.Locale.ROOT));
	}

	/** Inventory capacity assumed when the guide says "an inventory of X". */
	private static final int INVENTORY_SLOTS = 28;

	/**
	 * Spelled-out quantities the guide uses freely ("two cheese", "three raw
	 * beef", "eight redberries"). Without these the item resolves with quantity
	 * 1, so a step that needs several auto-completes the moment ONE is in the
	 * inventory. Only 1-12 plus a few round numbers occur in practice.
	 */
	private static final Map<String, Integer> WORD_NUMBERS = new HashMap<>();

	static
	{
		String[] ones = {"zero", "one", "two", "three", "four", "five", "six",
			"seven", "eight", "nine", "ten", "eleven", "twelve"};
		for (int i = 0; i < ones.length; i++)
		{
			WORD_NUMBERS.put(ones[i], i);
		}
		WORD_NUMBERS.put("a", 1);
		// "both buckets" is a quantity phrase, not an item called "both buckets"
		WORD_NUMBERS.put("both", 2);
		WORD_NUMBERS.put("another", 1);
		WORD_NUMBERS.put("each", 1);
		WORD_NUMBERS.put("either", 1);
		WORD_NUMBERS.put("an", 1);
		WORD_NUMBERS.put("dozen", 12);
		WORD_NUMBERS.put("fifteen", 15);
		WORD_NUMBERS.put("twenty", 20);
	}

	/**
	 * "16k feathers", "5k cannonballs", "1250k": a number with a 'k' thousands
	 * suffix. Expanded to the full count so the requirement is right (16000),
	 * not read as an item named "16k". Applied before the item name is cut.
	 */
	private static final Pattern K_SUFFIX = Pattern.compile("(?i)^(\\d+)k\\b\\s*(.*)$");

	/**
	 * Phrases naming a whole armour set or spell rune cost. These occur across
	 * many steps, so they expand here rather than as per-step overrides.
	 * Each entry is name then count, repeated.
	 */

	/**
	 * Spell name and phrase to rune cost, loaded from the bundled
	 * spell-runes.json. Guides write "Curse Runes" or "Vile Vigour" where an
	 * item belongs, so the runes a spell actually consumes are the useful
	 * requirement.
	 *
	 * <p>Loaded once, statically. If the resource is missing the maps stay
	 * empty and the parser falls back to its hardcoded phrases, so a packaging
	 * mistake degrades rather than breaks.</p>
	 */
	private static final Map<String, Map<String, Integer>> SPELL_RUNES = new HashMap<>();

	/** Guide phrasing to spell name, e.g. "curse runes" -> "Curse". */
	private static final Map<String, String> SPELL_PHRASES = new HashMap<>();

	static
	{
		try (java.io.InputStream in =
			ItemListParser.class.getResourceAsStream("spell-runes.json"))
		{
			if (in != null)
			{
				// instance API, not the static parseReader: the client bundles an
				// older Gson without it. CustomLocationStore documents the same
				// constraint.
				com.google.gson.JsonObject root = new com.google.gson.JsonParser()
					.parse(new java.io.InputStreamReader(
						in, java.nio.charset.StandardCharsets.UTF_8))
					.getAsJsonObject();
				com.google.gson.JsonObject spells = root.getAsJsonObject("spells");
				for (String spell : spells.keySet())
				{
					Map<String, Integer> runes = new java.util.LinkedHashMap<>();
					com.google.gson.JsonObject cost = spells.getAsJsonObject(spell);
					for (String rune : cost.keySet())
					{
						runes.put(rune, cost.get(rune).getAsInt());
					}
					SPELL_RUNES.put(Names.normalize(spell), runes);
					// the spell's own name is also a valid phrase
					SPELL_PHRASES.put(Names.normalize(spell), Names.normalize(spell));
				}
				com.google.gson.JsonObject aliases = root.getAsJsonObject("aliases");
				for (String phrase : aliases.keySet())
				{
					SPELL_PHRASES.put(Names.normalize(phrase),
						Names.normalize(aliases.get(phrase).getAsString()));
				}
			}
		}
		catch (Exception ignored)
		{
			// bundled data missing or malformed: fall back to hardcoded phrases
		}
	}

	/** Runes for a spell phrase, or null when the phrase names no spell. */
	private static String[][] spellRunes(String phrase)
	{
		String spell = SPELL_PHRASES.get(Names.normalize(phrase));
		if (spell == null)
		{
			return null;
		}
		Map<String, Integer> runes = SPELL_RUNES.get(spell);
		if (runes == null || runes.isEmpty())
		{
			return null;
		}
		String[][] out = new String[runes.size()][2];
		int i = 0;
		for (Map.Entry<String, Integer> e : runes.entrySet())
		{
			out[i][0] = e.getKey();
			out[i][1] = String.valueOf(e.getValue());
			i++;
		}
		return out;
	}

	private static String[][] setExpansion(String phrase)
	{
		switch (phrase.toLowerCase(java.util.Locale.ROOT).trim())
		{
			case "crystal armour":
			case "full crystal":
				return new String[][]{{"Crystal helm", "1"}, {"Crystal body", "1"},
					{"Crystal legs", "1"}};
			case "full initiate":
			case "initiate armour":
				return new String[][]{{"Initiate sallet", "1"}, {"Initiate hauberk", "1"},
					{"Initiate cuisse", "1"}};
			case "shayzien armour 5":
			case "t5 shayzien outfit":
			case "shayzien outfit":
				return new String[][]{{"Shayzien helm (5)", "1"}, {"Shayzien body (5)", "1"},
					{"Shayzien greaves (5)", "1"}, {"Shayzien gloves (5)", "1"},
					{"Shayzien boots (5)", "1"}};
			case "rogues outfit":
			case "rogue outfit":
				return new String[][]{{"Rogue mask", "1"}, {"Rogue top", "1"},
					{"Rogue trousers", "1"}, {"Rogue gloves", "1"}, {"Rogue boots", "1"}};
			// spell phrasings the guides use in an item position
			case "air spells runes":
			case "air spell runes":
				return new String[][]{{"Air rune", "1"}, {"Chaos rune", "1"}};
			case "death combat runes":
				return new String[][]{{"Death rune", "1"}, {"Air rune", "1"}};
			case "crumble undead runes":
			case "crumble undead":
				return new String[][]{{"Air rune", "2"}, {"Earth rune", "2"}, {"Chaos rune", "1"}};
			case "vile vigour":
				return new String[][]{{"Air rune", "3"}, {"Soul rune", "1"}};
			case "curse runes":
				return new String[][]{{"Earth rune", "3"}, {"Water rune", "2"}, {"Body rune", "1"}};
			case "humidify runes":
				return new String[][]{{"Fire rune", "1"}, {"Water rune", "3"}, {"Astral rune", "1"}};
			case "superglass make runes":
				return new String[][]{{"Air rune", "10"}, {"Fire rune", "6"}, {"Astral rune", "2"}};
			case "plank make runes":
				return new String[][]{{"Earth rune", "15"}, {"Astral rune", "2"}, {"Nature rune", "1"}};
			default:
				// everything else: consult the bundled spell table
				return spellRunes(phrase);
		}
	}

	/**
	 * Named teleport "runes" phrase ("trollheim teleport runes"), expanded to
	 * the actual runes for that teleport via {@link #teleportRuneSet}.
	 */
	private static final Pattern TELEPORT_RUNE_PHRASE = Pattern.compile(
		"(?i)^(.*?)\\s+teleport\\s+runes?$");

	/**
	 * Leading spelled-out quantity: returns {quantity, charsConsumed} or null.
	 * "a"/"an" count as 1 only when a noun follows, so "a spade" is quantity 1
	 * but a lone "a" is left alone.
	 */
	private static int[] leadingWordQuantity(String p)
	{
		int sp = p.indexOf(' ');
		if (sp <= 0)
		{
			return null;
		}
		String first = p.substring(0, sp).toLowerCase(java.util.Locale.ROOT);
		Integer n = WORD_NUMBERS.get(first);
		if (n == null)
		{
			return null;
		}
		return new int[]{n, sp + 1};
	}

	/**
	 * Normalized forms of the prose drop-list, so smart quotes and punctuation
	 * ("it's possible" with a curly apostrophe) still match.
	 */
	private static final Set<String> NOT_ITEMS_NORM = new HashSet<>();

	static
	{
		for (String s : NOT_ITEMS_PROSE)
		{
			NOT_ITEMS_NORM.add(Names.normalize(s));
		}
	}

	// stop the item name at travel/location phrasing
	private static final Pattern GATHER_CUTOFF = Pattern.compile(
		"(?i)\\s+(?:off|from|inside|outside|at|on|next to|near|beneath|below|above|under|behind|"
		+ "in|to|south|north|east|west|whenever|if|while|then|when|during|after|before|"
		+ "and wield|and equip|by|for)\\b.*$");

	// every expression below runs once per step during import - precompiled
	// so a large guide doesn't compile thousands of throwaway Patterns
	private static final Pattern SLOTS_PAREN = Pattern.compile("(?i)\\s*\\([^)]*slots?[^)]*\\)");
	private static final Pattern SLOTS_BRACKET = Pattern.compile("(?i)\\s*\\[[^\\]]*slots?[^\\]]*]");
	private static final Pattern SLOTS_UNCLOSED = Pattern.compile("(?i)\\s*\\([^)]*slots?.*$");
	private static final Pattern TRAILING_DOTS = Pattern.compile("[.\\s]+$");
	/**
	 * List separators. The '+' is a separator between items ("Spade + Range
	 * Gear") EXCEPT when it directly follows a digit, where it means "at least
	 * this many" ("10+ Nails", "50+ Earth Runes"). Without the lookbehind the
	 * split produced an item named "10" and another named "Nails".
	 */
	/**
	 * List separators: comma, ampersand, "+" (except after a digit, where it
	 * means "at least"), and the word "and". BRUHsailer-style guides write
	 * "two cheese, eight redberries and one banana", so "and" must split or the
	 * trailing items merge into one and their quantities are lost. Bounded by
	 * spaces so it never splits inside a word like "Grand" or "Wand".
	 */
	private static final Pattern LIST_SPLIT = Pattern.compile(
		",|&|(?<!\\d)\\+|\\s+and\\s+");
	private static final Pattern INSTRUCTION_FRAGMENT = Pattern.compile(
		"(?i)^(?:wear|wield|equip|use|then|and|if|climb|go|run|walk|talk|return|"
			+ "bank|deposit|bury|teleport|travel|head|enter|leave|cross|open|close)\\b.*");
	/**
	 * A requirement phrase, not an instruction. It must survive the instruction
	 * filter so {@link #emit} can turn a destination-free phrase into the honest
	 * minimum requirement (one law rune).
	 */
	private static final Pattern TELEPORT_RUNES_REQUIREMENT = Pattern.compile(
		"(?i)^teleport\\s+runes?(?:\\s+.*)?$");
	private static final Pattern GATHER_BOUNDARY = Pattern.compile("[.\\[(,&+]");
	private static final Pattern LEADING_ARTICLE = Pattern.compile("^(?:the|a|an)\\s+");

	private static final Set<String> NOT_ITEMS = new HashSet<>(Arrays.asList(
		"everything", "all", "it", "them", "both", "one", "this", "these"));

	private ItemListParser()
	{
	}

	/**
	 * Drops ONE trailing "(advice here)" parenthetical - but NEVER a short
	 * dose/charge suffix like "Prayer potion(4)", "Ring of dueling(8)" or
	 * "(t)", whose parenthetical is part of the real item name. Advice
	 * contains whitespace or is 8+ characters; suffixes are short and solid.
	 *
	 * A single left-to-right scan replacing the old
	 * {@code \s*\((?:[^)]*\s[^)]*|[^)]{8,})\)\s*$} regex, whose overlapping
	 * unbounded quantifiers backtracked quadratically on malformed
	 * whitespace-heavy text with an unclosed parenthesis (measured ~0.5s at
	 * 10k chars - enough to stall a guide import). Same semantics: the
	 * stripped region must end the string, its content can't contain a
	 * closing parenthesis, and like the regex's leftmost match it starts at
	 * the EARLIEST open parenthesis after the second-to-last close - so an
	 * unbalanced "(see (note)" strips whole, never leaving a dangling "(see".
	 */
	static String stripTrailingAdvice(String value)
	{
		int end = value.length();
		while (end > 0 && Character.isWhitespace(value.charAt(end - 1)))
		{
			end--;
		}
		if (end == 0 || value.charAt(end - 1) != ')')
		{
			return value;
		}
		int lastClose = value.lastIndexOf(')', end - 2);
		int open = value.indexOf('(', lastClose + 1);
		if (open < 0 || open >= end - 1)
		{
			return value;
		}
		String inner = value.substring(open + 1, end - 1);
		boolean advice = inner.length() >= 8;
		for (int i = 0; !advice && i < inner.length(); i++)
		{
			advice = Character.isWhitespace(inner.charAt(i));
		}
		if (!advice)
		{
			return value;
		}
		// also consume the whitespace that preceded the parenthetical
		while (open > 0 && Character.isWhitespace(value.charAt(open - 1)))
		{
			open--;
		}
		return value.substring(0, open);
	}

	/**
	 * Full multi-item list from a "Withdraw:" step, or null when this isn't one.
	 */
	public static List<ItemReq> parseWithdraw(String text)
	{
		if (text == null || text.length() > MAX_ITEM_PARSE_CHARS)
		{
			return null;
		}
		Matcher m = WITHDRAW.matcher(stripWikiLinks(text).trim());
		if (!m.matches())
		{
			return null;
		}
		// drop "(8 Inventory slots)" style notes ANYWHERE in the list -
		// the live guide often continues after them ("... (14 Inventory
		// Slots) + Food"), so trailing-only stripping isn't enough; the third
		// covers a writer who forgot the closing parenthesis
		String list = DIALOGUE_OPTIONS.matcher(m.group(1)).replaceAll("");
		list = GROUPED_THOUSANDS.matcher(list).replaceAll("");
		list = protectAndNames(list);
		list = SLOTS_PAREN.matcher(list).replaceAll("");
		list = SLOTS_BRACKET.matcher(list).replaceAll("");
		list = SLOTS_UNCLOSED.matcher(list).replaceAll("");
		list = stripTrailingAdvice(list);
		list = TRAILING_DOTS.matcher(list).replaceAll("");

		return parseList(list);
	}

	/**
	 * Items list from a JSON guide's " (Items: 100 gp, knife, logs)" step
	 * suffix (appended from its items_needed metadata), or null when the step
	 * has none. Display/bank-tag/icon use ONLY - unlike a Withdraw step this
	 * never becomes an auto-completion condition, because holding the items is
	 * not what completes such a step.
	 */
	public static List<ItemReq> parseItemsSuffix(String text)
	{
		if (text == null)
		{
			return null;
		}
		int idx = text.lastIndexOf("(Items: ");
		if (idx < 0)
		{
			return null;
		}
		String inner = text.substring(idx + "(Items: ".length()).trim();
		// the step may have been length-capped mid-suffix ("...…"): parse the
		// items that survived instead of dropping the whole list
		boolean truncated = false;
		if (inner.endsWith("…"))
		{
			inner = inner.substring(0, inner.length() - 1);
			truncated = true;
		}
		if (inner.endsWith(")"))
		{
			inner = inner.substring(0, inner.length() - 1);
			truncated = false; // the list closed before the cap hit
		}
		List<ItemReq> out = parseList(inner);
		if (truncated && out != null && !out.isEmpty())
		{
			// the cap cut mid-list: the final fragment ("kni…") is not a real
			// item name - drop it rather than show a bogus grid entry
			out.remove(out.size() - 1);
			if (out.isEmpty())
			{
				return null;
			}
		}
		return out;
	}

	/** Split a comma/&amp;/+-separated item list into requirements. */
	private static List<ItemReq> parseList(String list)
	{
		List<ItemReq> out = new ArrayList<>();
		// the guide separates items with commas, ampersands, and plus signs
		for (String part : LIST_SPLIT.split(list))
		{
			// a curated override can also name a single LIST SEGMENT
			// ("Cure Me Spells", "Curse Runes"): those phrases only ever
			// occur inside longer withdraw lines, so the whole-step lookup
			// in parse() never sees them - expand them here instead
			List<ItemReq> segmentOverride = StepItemOverrides.forStep(part.trim());
			if (segmentOverride != null)
			{
				out.addAll(segmentOverride);
				continue;
			}
			String p = stripTrailingAdvice(part.trim());
			// trailing sentences ("Ball of Wool. If 85 Crafting...")
			int sentence = p.indexOf(". ");
			if (sentence > 0)
			{
				p = p.substring(0, sentence);
			}
			p = p.trim();
			if (p.isEmpty())
			{
				continue;
			}
			// instruction fragments after '&' splits ("wear them", "equip it").
			// "Teleport Runes" is an item requirement despite beginning with an
			// instruction verb; it must reach emit() instead of being discarded.
			if (INSTRUCTION_FRAGMENT.matcher(p).matches()
				&& !TELEPORT_RUNES_REQUIREMENT.matcher(p).matches())
			{
				continue;
			}
			// strip a trailing "(...)" qualifier whose insides would otherwise be
			// mistaken for list separators ("Potion (3)/(4)")
			String stripped = TRAILING_QUALIFIER.matcher(p).replaceAll("");
			if (!stripped.equals(p) && !stripped.trim().isEmpty())
			{
				p = stripped.trim();
			}
			// "Research Notes / Bronze pickaxe not needed": the slash separates
			// a real item from an explicit negation, so keep only what's needed
			if (p.indexOf('/') >= 0)
			{
				expandSlashGroup(out, p);
				continue;
			}
			if (NEGATED.matcher(p).find())
			{
				continue;
			}
			// "2x Inventory of X" must be read whole: letting the generic
			// quantity prefix consume the "2x" first loses the multiplier
			if (INVENTORY_OF.matcher(p).matches())
			{
				emit(out, p, 1, null);
				continue;
			}
			int qty = 1;
			String qtyLabel = null;
			Matcher q = QTY_PREFIX.matcher(p);
			if (q.matches())
			{
				try
				{
					qty = Math.max(1, Integer.parseInt(q.group(1)));
				}
				catch (NumberFormatException ignored)
				{
				}
				if (!q.group(2).isEmpty())
				{
					// "100+ Cosmic Runes": 100 satisfies the step, the label
					// carries the guide's real open-ended intent
					qtyLabel = q.group(1) + "+";
				}
				p = q.group(3).trim();
			}
			else
			{
				Matcher ks = K_SUFFIX.matcher(p);
				if (ks.matches() && !ks.group(2).trim().isEmpty())
				{
					try
					{
						qty = Math.max(1, Integer.parseInt(ks.group(1)) * 1000);
					}
					catch (NumberFormatException ignored)
					{
					}
					p = ks.group(2).trim();
				}
				else
				{
					// spelled-out quantity ("two cheese", "three raw beef")
					int[] wq = leadingWordQuantity(p);
					if (wq != null)
					{
						qty = Math.max(1, wq[0]);
						p = p.substring(wq[1]).trim();
					}
				}
			}
			emit(out, p, qty, qtyLabel);
		}
		if (out.isEmpty())
		{
			return null;
		}
		applyInventoryFillThresholds(out);
		return out;
	}

	/**
	 * "Inventory of X" still displays the guide's literal 28-slot request.
	 * When the same step also requests another item, half an inventory is the
	 * practical readiness/completion threshold: 14 for one inventory, 28 for
	 * two inventories. This is deliberately narrow and never changes the
	 * displayed stack count.
	 */
	private static void applyInventoryFillThresholds(List<ItemReq> items)
	{
		boolean hasOtherRequirement = false;
		for (ItemReq req : items)
		{
			if (!req.isInventoryFill() && !ConceptItems.isConcept(req.getName()))
			{
				hasOtherRequirement = true;
				break;
			}
		}
		if (!hasOtherRequirement)
		{
			return;
		}
		for (int i = 0; i < items.size(); i++)
		{
			ItemReq req = items.get(i);
			if (req.isInventoryFill())
			{
				items.set(i, req.withCompletionQuantity(Math.max(1, req.getQuantity() / 2)));
			}
		}
	}


	/**
	 * Expands a slash-joined fragment. Two shapes occur in the guide:
	 *
	 * <p>A LEADING qualifier shared by every part - "Raw Rat/Chicken/Beef"
	 * means raw rat, raw chicken and raw beef. A TRAILING noun shared by every
	 * part - "Mind/6xAir/6xEarth/All Water Runes" means mind rune, 6 air runes,
	 * 6 earth runes and all water runes. Parts that already carry the shared
	 * word keep their own copy rather than doubling it.</p>
	 *
	 * <p>Explicit negations ("Bronze pickaxe not needed") are dropped, which is
	 * what makes a slash also usable as an alternatives separator.</p>
	 */
	private static void expandSlashGroup(List<ItemReq> out, String fragment)
	{
		String[] parts = fragment.split("/");
		if (parts.length < 2)
		{
			expand(out, fragment);
			return;
		}
		String first = parts[0].trim();
		String last = parts[parts.length - 1].trim();

		// trailing shared noun: last part's final word, when the earlier parts
		// are bare enough to need it ("Mind" -> "Mind Runes")
		String tailNoun = null;
		int lastSpace = last.lastIndexOf(' ');
		if (lastSpace > 0)
		{
			tailNoun = last.substring(lastSpace + 1).trim();
		}

		// leading shared qualifier: first part's first word, when the later
		// parts are single bare words ("Raw Rat" -> "Raw Chicken")
		String headWord = null;
		int firstSpace = first.indexOf(' ');
		if (firstSpace > 0)
		{
			headWord = first.substring(0, firstSpace).trim();
		}
		boolean tailShared = tailNoun != null && !tailNoun.isEmpty()
			&& parts.length > 1 && !containsWord(first, tailNoun);
		boolean headShared = headWord != null && !headWord.isEmpty()
			&& !containsWord(last, headWord) && !tailShared;

		for (String raw : parts)
		{
			String a = raw.trim();
			if (a.isEmpty() || NEGATED.matcher(a).find())
			{
				continue;
			}
			if (headShared && a != first && !containsWord(a, headWord))
			{
				a = headWord + " " + a;
			}
			else if (tailShared && !containsWord(a, tailNoun))
			{
				a = a + " " + tailNoun;
			}
			expand(out, a);
		}
	}

	/**
	 * Runes for a named teleport, or null if not one we expand. Counts verified
	 * against the Teleportation_spells wiki table.
	 */
	private static String[][] teleportRuneSet(String place)
	{
		switch (place)
		{
			case "trollheim":
				return new String[][]{{"2", "Fire rune"}, {"2", "Law rune"}};
			case "varrock":
				return new String[][]{{"3", "Air rune"}, {"1", "Fire rune"}, {"1", "Law rune"}};
			case "falador":
				return new String[][]{{"3", "Air rune"}, {"1", "Water rune"}, {"1", "Law rune"}};
			case "lumbridge":
				return new String[][]{{"3", "Air rune"}, {"1", "Earth rune"}, {"1", "Law rune"}};
			case "camelot":
				return new String[][]{{"5", "Air rune"}, {"1", "Law rune"}};
			case "ardougne":
				return new String[][]{{"2", "Water rune"}, {"2", "Law rune"}};
			case "watchtower":
				return new String[][]{{"2", "Earth rune"}, {"2", "Law rune"}};
			case "house":
			case "teleport to house":
				return new String[][]{{"1", "Air rune"}, {"1", "Earth rune"}, {"1", "Law rune"}};
			default:
				return null;
		}
	}

	private static boolean lowerStarts(String s, String prefix)
	{
		return s.length() >= prefix.length()
			&& s.substring(0, prefix.length()).equalsIgnoreCase(prefix);
	}

	private static boolean containsWord(String haystack, String word)
	{
		String h = " " + haystack.toLowerCase(java.util.Locale.ROOT) + " ";
		return h.contains(" " + word.toLowerCase(java.util.Locale.ROOT) + " ");
	}

	/**
	 * Adds one parsed fragment, applying the guide's open-ended and derived
	 * quantity conventions. Fragments that survive to here are already
	 * de-negated and slash-split.
	 */
	private static void emit(List<ItemReq> out, String p, int qty, String qtyLabel)
	{
		emit(out, p, qty, qtyLabel, 0);
	}

	/**
	 * The rewrite rules ("All X", "Inventory of X", "Best X Spell") re-emit
	 * their stripped noun, so degenerate input ("all all all ...") recurses
	 * once per token. Real steps never nest past two; the depth cap turns a
	 * hostile 4k-char chain from a StackOverflowError into a dropped item.
	 */
	private static final int MAX_EMIT_DEPTH = 8;

	private static void emit(List<ItemReq> out, String p, int qty, String qtyLabel, int depth)
	{
		if (p == null || depth > MAX_EMIT_DEPTH)
		{
			return;
		}
		p = p.trim();
		if (p.isEmpty())
		{
			return;
		}

		// "All Water Runes": any amount satisfies the step, so the numeric
		// requirement is 1 and the label carries the guide's real intent
		Matcher all = ALL_QTY.matcher(p);
		if (all.matches())
		{
			emit(out, all.group(1).trim(), 1, "all", depth + 1);
			return;
		}

		// "2x Inventory of Waterskins(4)" -> 2 * 28
		Matcher inv = INVENTORY_OF.matcher(p);
		if (inv.matches())
		{
			int multiples = 1;
			if (inv.group(1) != null)
			{
				try
				{
					multiples = Math.max(1, Integer.parseInt(inv.group(1)));
				}
				catch (NumberFormatException ignored)
				{
				}
			}
			int before = out.size();
			emit(out, inv.group(2).trim(), multiples * INVENTORY_SLOTS, null, depth + 1);
			for (int i = before; i < out.size(); i++)
			{
				out.set(i, out.get(i).asInventoryFill());
			}
			return;
		}

		// "1 extra Rotten Apple" - one MORE than the one the step already needs
		Matcher extra = EXTRA_QTY.matcher(p);
		if (extra.matches())
		{
			int n = 1;
			try
			{
				n = Math.max(1, Integer.parseInt(extra.group(1)));
			}
			catch (NumberFormatException ignored)
			{
			}
			emit(out, extra.group(2).trim(), n + 1, null, depth + 1);
			return;
		}

		// restore any " and " that was masked to survive the list split
		if (p.indexOf('\u0001') >= 0)
		{
			p = p.replace(AND_GUARD, " and ");
		}

		// A bare charged/dosed phrase accepts ANY dose. An explicitly written
		// "(4)" in the guide is left exact. Previously a bare phrase was pinned
		// to one dose, so holding a 3-dose potion counted as nothing.
		String[] anyDose = DOSE_GROUPS.get(Names.normalize(p));
		if (anyDose != null)
		{
			out.add(ItemReq.anyOf(anyDose[0], Math.max(1, qty), qtyLabel, anyDose));
			return;
		}

		// "Kitten or Cat" and "Food for Elvarg" name a choice and a category,
		// not one exact item
		String bare = Names.normalize(p);
		if (bare.equals("kittenorcat") || bare.equals("catorkitten"))
		{
			out.add(ItemReq.anyOf("Pet kitten", Math.max(1, qty), qtyLabel,
				"Pet kitten", "Pet cat", "Cat", "Kitten"));
			return;
		}
		if (bare.equals("teleportrunes") || bare.equals("teleportrune"))
		{
			// A bare "Teleport Runes" begins with the verb "teleport", so the
			// instruction-fragment filter used to discard it before emit() ran.
			// Every standard teleport needs a law rune; use that honest minimum
			// as an exact requirement and a real icon.
			out.add(new ItemReq("Law rune", Math.max(1, qty), qtyLabel));
			return;
		}
		if (bare.equals("batteredkeyorknife"))
		{
			// two real items, either satisfies the step
			out.add(ItemReq.anyOf("Battered key", Math.max(1, qty), qtyLabel,
				"Battered key", "Knife"));
			return;
		}
		// phrases that name an item FAMILY: any tier counts, and the slot shows
		// the lowest tier
		ItemCategory family = FAMILY_PHRASES.get(bare);
		if (family != null)
		{
			out.add(ItemReq.category(family, Math.max(1, qty), qtyLabel));
			return;
		}

		// FIXED-size phrases carry their own count and must never be multiplied
		// by a quantity the parser already pulled off the front: "5 Coloured
		// Balls" was becoming 25 because both fives were applied.
		String fixedKey = Names.normalize(p);
		if (fixedKey.equals("colouredballs") || fixedKey.equals("5colouredballs")
			|| fixedKey.equals("all5colouredballs") || fixedKey.equals("coloredballs"))
		{
			emit(out, "Stone ball", 5, null);
			return;
		}

		// whole-set and spell phrases expand into their real components
		String[][] expansion = setExpansion(p);
		if (expansion != null)
		{
			for (String[] piece : expansion)
			{
				emit(out, piece[0], Integer.parseInt(piece[1]) * Math.max(1, qty), null);
			}
			return;
		}

		// "Trollheim teleport runes" -> the runes that teleport actually uses
		Matcher teleRunes = TELEPORT_RUNE_PHRASE.matcher(p);
		if (teleRunes.matches())
		{
			String place = teleRunes.group(1).toLowerCase(java.util.Locale.ROOT).trim();
			String[][] set = teleportRuneSet(place);
			if (set != null)
			{
				for (String[] rune : set)
				{
					emit(out, rune[1], Integer.parseInt(rune[0]), null, depth + 1);
				}
				return;
			}
			// A bare "Teleport Runes" with no destination used to fall through
			// here and get dropped entirely, so banks 102 and 105 showed no
			// teleport requirement at all. Every teleport spell needs a law
			// rune, so that is the honest minimum and gives the slot a real icon.
			if (place.isEmpty())
			{
				emit(out, "Law rune", Math.max(1, qty), qtyLabel, depth + 1);
				return;
			}
			// named but unknown destination: leave as a generic runes label
			// rather than guess at the elemental runes
		}

		// "Best Air Spell" -> the elemental rune it needs
		Matcher bestSpell = BEST_SPELL.matcher(p);
		if (bestSpell.matches())
		{
			emit(out, bestSpell.group(1) + " rune", 1, null, depth + 1);
			return;
		}
		// "best food" / "best X" where X is a plain noun: drop the qualifier
		if (lowerStarts(p, "best "))
		{
			emit(out, p.substring(5).trim(), qty, qtyLabel, depth + 1);
			return;
		}

		// "Few food", "as many bones as possible": keep the guide's own word as
		// the label; the numeric requirement stays 1 so any amount satisfies it
		Matcher vague = VAGUE_QTY.matcher(p);
		if (vague.matches())
		{
			String word = vague.group(1).toLowerCase(java.util.Locale.ROOT);
			String rest = RANDOM_FILLER.matcher(vague.group(2)).replaceAll("").trim();
			emit(out, rest, 1, "as many".equals(word) ? "all" : word, depth + 1);
			return;
		}

		// "60 Noted Empty Buckets" - the noted form of the same item
		Matcher noted = NOTED.matcher(p);
		if (noted.matches())
		{
			emit(out, noted.group(1).trim(), qty, qtyLabel, depth + 1);
			return;
		}

		// length cap: real item names are short; oversized "names" are
		// malformed wiki text and would only bloat tooltips/grids
		// "Goblin Diplomacy]" - the opening bracket went to another fragment,
		// so what is left is quest-tag text, not an item
		if (ORPHAN_BRACKET.matcher(p).matches())
		{
			return;
		}
		String lower = p.toLowerCase(java.util.Locale.ROOT);
		String norm = Names.normalize(p);
		if (p.length() > 60 || NOT_ITEMS.contains(lower) || NOT_ITEMS_TRANSPORT.contains(lower)
			|| NOT_ITEMS_PROSE.contains(lower) || NOT_ITEMS_NORM.contains(norm))
		{
			return;
		}
		out.add(qtyLabel != null ? new ItemReq(p, qty, qtyLabel) : new ItemReq(p, qty));
	}


	/** Replaces [[Target|Label]] and [[Target]] with their display text. */
	static String stripWikiLinks(String text)
	{
		if (text == null || text.indexOf("[[") < 0)
		{
			return text == null ? "" : text;
		}
		StringBuilder sb = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length())
		{
			int open = text.indexOf("[[", i);
			if (open < 0)
			{
				sb.append(text, i, text.length());
				break;
			}
			int close = text.indexOf("]]", open);
			if (close < 0)
			{
				sb.append(text, i, text.length());
				break;
			}
			sb.append(text, i, open);
			String inner = text.substring(open + 2, close);
			int pipe = inner.lastIndexOf('|');
			sb.append(pipe >= 0 ? inner.substring(pipe + 1) : inner);
			i = close + 2;
		}
		return sb.toString();
	}

	/**
	 * Splits a slash-joined fragment whose parts share a trailing noun
	 * ("Raw Rat/Chicken/Beef" -> three raw meats, "Desert Robes/boots" -> two
	 * desert pieces) and emits each. Falls back to emitting the fragment
	 * unchanged when no shared noun is detectable.
	 */
	private static void expand(List<ItemReq> out, String fragment)
	{
		int qty = 1;
		String qtyLabel = null;
		Matcher q = QTY_PREFIX.matcher(fragment);
		if (q.matches())
		{
			try
			{
				qty = Math.max(1, Integer.parseInt(q.group(1)));
			}
			catch (NumberFormatException ignored)
			{
			}
			if (!q.group(2).isEmpty())
			{
				qtyLabel = q.group(1) + "+";
			}
			fragment = q.group(3).trim();
		}
		emit(out, fragment, qty, qtyLabel);
	}

	/**
	 * Single item from a gathering step ("Collect 3x Logs ..."), or null.
	 * Only applies when the step STARTS with the verb, to avoid false positives
	 * in longer sentences.
	 */
	public static List<ItemReq> parseGather(String text)
	{
		if (text == null || text.length() > MAX_ITEM_PARSE_CHARS)
		{
			return null;
		}
		Matcher m = GATHER_VERB.matcher(protectAndNames(
			DIALOGUE_OPTIONS.matcher(stripWikiLinks(text)).replaceAll("").trim()));
		if (!m.matches())
		{
			return null;
		}
		String rest = m.group(1).trim();

		// "1 extra Rotten Apple" must be seen BEFORE the quantity prefix is
		// consumed, otherwise "extra" survives as the item name
		Matcher extraFirst = EXTRA_QTY.matcher(rest);
		if (extraFirst.matches())
		{
			int n = 1;
			try
			{
				n = Math.max(1, Integer.parseInt(extraFirst.group(1)));
			}
			catch (NumberFormatException ignored)
			{
			}
			String name = extraFirst.group(2).trim();
			String[] cut = GATHER_BOUNDARY.split(name);
			name = cut.length > 0 ? cut[0].trim() : "";
			name = GATHER_CUTOFF.matcher(name).replaceAll("").trim();
			List<ItemReq> extraOut = new ArrayList<>();
			emit(extraOut, name, n + 1, null);
			return extraOut.isEmpty() ? null : extraOut;
		}

		int qty = 1;
		String qtyLabel = null;
		Matcher q = QTY_PREFIX.matcher(rest);
		if (q.matches())
		{
			try
			{
				qty = Math.max(1, Integer.parseInt(q.group(1)));
			}
			catch (NumberFormatException ignored)
			{
			}
			if (!q.group(2).isEmpty())
			{
				// "Buy 100+ Cosmic Runes": the open-quantity label was dropped
				// here while the withdraw path kept it, so the same phrase
				// rendered differently depending on the verb
				qtyLabel = q.group(1) + "+";
			}
			String remainder = q.group(3).trim();
			// "Buy 2x Inventory of Waterskins(4)": the leading count multiplies
			// INVENTORIES, not items, so hand the whole phrase to emit() intact
			// rather than letting it be read as a plain item quantity
			if (INVENTORY_OF.matcher(remainder).matches())
			{
				List<ItemReq> invOut = new ArrayList<>();
				emit(invOut, qty + "x " + remainder, 1, null);
				if (invOut.isEmpty())
				{
					return null;
				}
				applyInventoryFillThresholds(invOut);
				return invOut;
			}
			rest = remainder;
		}

		// A gather step can name several items ("Buy 3x Buckets & Shears"), so
		// split on the same separators a withdraw list uses BEFORE cutting at
		// sentence/location boundaries - the old code kept only the first
		// fragment and silently dropped everything after the ampersand.
		List<ItemReq> out = new ArrayList<>();
		String[] segments = LIST_SPLIT.split(GROUPED_THOUSANDS.matcher(rest).replaceAll(""));
		String[] distributed = distributeTrailingNoun(segments);
		if (distributed != null)
		{
			segments = distributed;
		}
		for (int i = 0; i < segments.length; i++)
		{
			String seg = segments[i].trim();
			if (seg.isEmpty())
			{
				continue;
			}
			// possessives and hedges the guide puts before a count
			seg = LEADING_HEDGE.matcher(seg).replaceFirst("").trim();

			// This segment's OWN quantity prefix must be taken before the
			// boundary split: GATHER_BOUNDARY treats '+' as a boundary, so
			// "100+ Cosmic Runes" was cut down to "100" and the item was lost.
			int segPrefixQty = 0;
			String segPrefixLabel = null;
			Matcher segQ = QTY_PREFIX.matcher(seg);
			if (segQ.matches())
			{
				try
				{
					segPrefixQty = Math.max(1, Integer.parseInt(segQ.group(1)));
				}
				catch (NumberFormatException ignored)
				{
				}
				if (!segQ.group(2).isEmpty())
				{
					segPrefixLabel = segQ.group(1) + "+";
				}
				seg = segQ.group(3).trim();
			}

			// bracket/parenthesis boundaries end the item name
			String[] bits = GATHER_BOUNDARY.split(seg);
			String name = bits.length > 0 ? bits[0].trim() : "";
			name = GATHER_CUTOFF.matcher(name).replaceAll("").trim();
			// k-suffix ("16k feathers") and spelled-out quantity ("three raw
			// beef") on THIS segment before the article strip, so each item in
			// a multi-item gather keeps its own count instead of defaulting to 1
			int segWordQty = 0;
			Matcher segK = K_SUFFIX.matcher(name);
			if (segK.matches() && !segK.group(2).trim().isEmpty())
			{
				try
				{
					segWordQty = Integer.parseInt(segK.group(1)) * 1000;
				}
				catch (NumberFormatException ignored)
				{
				}
				name = segK.group(2).trim();
			}
			else
			{
				int[] wq = leadingWordQuantity(name);
				if (wq != null)
				{
					segWordQty = wq[0];
					name = name.substring(wq[1]).trim();
				}
			}
			name = LEADING_ARTICLE.matcher(name).replaceFirst("").trim();
			// the 30-char cap targets runaway prose, but "Full Inventory of
			// Balls of wool" is a legitimate long form that emit() rewrites
			// into a short item name - so let those through
			if (name.isEmpty()
				|| (name.length() > 30 && !INVENTORY_OF.matcher(name).matches()))
			{
				continue;
			}
			if (INSTRUCTION_FRAGMENT.matcher(name).matches())
			{
				continue;
			}
			// precedence: this segment's own prefix, then a spelled-out number,
			// then the verb's leading count on segment 0, else 1
			int segQty = segPrefixQty > 0 ? segPrefixQty
				: (segWordQty > 0 ? segWordQty : (i == 0 ? qty : 1));
			// the verb-level open-quantity label ("Buy 100+ Cosmic Runes")
			// belongs to segment 0, the same way its count does
			String segLabel = segPrefixLabel != null ? segPrefixLabel
				: (i == 0 && segPrefixQty == 0 && segWordQty == 0 ? qtyLabel : null);
			if (name.indexOf('/') >= 0)
			{
				// "100 Soda Ash/Sand": every branch of the group inherits the
				// shared leading count instead of silently becoming 1
				List<ItemReq> group = new ArrayList<>();
				expandSlashGroup(group, name);
				for (ItemReq r : group)
				{
					emit(out, r.getName(),
						segQty > 1 ? segQty : r.getQuantity(), segLabel);
				}
			}
			else if (segLabel != null)
			{
				emit(out, name, segQty, segLabel);
			}
			else
			{
				expand(out, segQty > 1 ? segQty + "x " + name : name);
			}
		}
		if (out.isEmpty())
		{
			return null;
		}
		applyInventoryFillThresholds(out);
		return out;
	}

	/**
	 * Withdraw list if present, otherwise gather item, otherwise null.
	 *
	 * <p>A step-specific override wins over both, for the handful of guide
	 * sentences whose wording no general rule resolves correctly. An override
	 * that no longer matches falls through to normal parsing.</p>
	 */
	/**
	 * Hostile-input bound for the regex pipeline. Several cleanup patterns
	 * ({@code SLOTS_*}, {@code TRAILING_QUALIFIER}) scan quadratically on
	 * pathological "((((("-flooded text; real guide item lines top out around
	 * 230 characters, so anything past this cap is malformed or hostile and
	 * produces no items at all rather than minutes of executor time.
	 * (Measured: 350ms per 4k-char flooded step without the cap.)
	 */
	private static final int MAX_ITEM_PARSE_CHARS = 512;

	public static List<ItemReq> parse(String text)
	{
		List<ItemReq> override = StepItemOverrides.forStep(text);
		if (override != null)
		{
			return override;
		}
		List<ItemReq> w = parseWithdraw(text);
		return w != null ? w : parseGather(text);
	}
}
