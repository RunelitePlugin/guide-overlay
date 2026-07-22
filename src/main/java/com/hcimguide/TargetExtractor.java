package com.hcimguide;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a likely NPC target name from a step's text, e.g.
 * "Talk to Father Urhney in the lumbridge swamp ..." -&gt; "Father Urhney".
 *
 * Extraction runs once per step when a guide is imported (results are
 * precomputed and cached by the plugin); matching helpers run per tick.
 * All patterns are precompiled.
 */
public final class TargetExtractor
{
	private static final Pattern VERB = Pattern.compile(
		"(?i)\\b(?:talk to|speak to|speak with|talk with|trade with|trade|kill|attack|pickpocket)\\s+(?:the\\s+)?([A-Z][A-Za-z'’()_-]*(?:\\s+[A-Z][A-Za-z'’()_-]*)*)");

	// words that end a name if the guide continues the sentence in lowercase
	private static final Pattern TRAILING = Pattern.compile(
		"(?i)\\s+(?:and|&|in|at|for|to|then|with|on|near|inside|outside|again|whenever|north|south|east|west)\\b.*$");

	private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*\\([^)]*\\)\\s*$");
	private static final Pattern TRAILING_JUNK = Pattern.compile("[^A-Za-z'’)]+$");

	private TargetExtractor()
	{
	}

	public static String extract(String stepText)
	{
		if (stepText == null)
		{
			return null;
		}
		Matcher m = VERB.matcher(stepText);
		if (!m.find())
		{
			return null;
		}
		String name = m.group(1);
		name = TRAILING.matcher(name).replaceAll("");
		// strip dialogue notation / disambiguation leftovers
		name = name.replace('_', ' ');
		name = TRAILING_PAREN.matcher(name).replaceAll("");
		name = TRAILING_JUNK.matcher(name).replaceAll("").trim();
		if (name.length() < 2 || name.length() > 32)
		{
			return null;
		}
		return name;
	}

	/** Case-insensitive comparison tolerant of underscores and extra spaces. */
	public static boolean namesMatch(String guideName, String npcName)
	{
		return Names.matchNormalized(Names.normalize(guideName), Names.normalize(npcName));
	}

	/**
	 * Normalized names of interactable scene objects worth tracking for the
	 * step-object highlighter (ladders, altars, doors, ...). A fixed
	 * whitelist rather than verb-based extraction: object phrasing varies
	 * wildly and a whitelist can't false-positive onto NPC or item names.
	 * The scene tracker only remembers objects whose name is in this set,
	 * which keeps its bookkeeping to a handful of objects per scene.
	 */
	static final java.util.Set<String> OBJECT_WORDS;

	/** Guide-text word -&gt; the scene-object names it should light up. */
	private static final java.util.Map<String, String[]> OBJECT_EXPANSIONS =
		new java.util.HashMap<>();

	/** The raw (spaced, lowercase) forms scanned for in step text. */
	private static final String[] OBJECT_WORD_LIST = {
		"ladder", "staircase", "stairs", "altar", "door", "gate", "trapdoor",
		"tunnel", "cave entrance", "portal", "lever", "chest",
		"furnace", "anvil", "cooking range", "spinning wheel", "loom",
		"fountain", "obelisk", "fairy ring", "spirit tree", "ropeswing",
		"stile", "stepping stone", "log balance", "crevice",
		"bank booth", "bank chest", "deposit box", "sand pit", "hopper",
		"dark hole", "manhole", "pottery wheel", "kiln", "spiderweb"};

	static
	{
		// words whose in-scene object name differs from the guide's wording
		OBJECT_EXPANSIONS.put("stairs", new String[]{"stairs", "staircase"});
		OBJECT_EXPANSIONS.put("staircase", new String[]{"staircase", "stairs"});
		OBJECT_EXPANSIONS.put("cooking range", new String[]{"cooking range", "range"});
		OBJECT_EXPANSIONS.put("spiderweb", new String[]{"spiderweb", "web"});
		OBJECT_EXPANSIONS.put("stepping stone", new String[]{"stepping stone", "stepping stones"});
		OBJECT_EXPANSIONS.put("deposit box", new String[]{"deposit box", "bank deposit box"});
		OBJECT_EXPANSIONS.put("door", new String[]{"door", "large door"});

		java.util.Set<String> w = new java.util.HashSet<>();
		for (String s : OBJECT_WORD_LIST)
		{
			w.add(Names.normalize(s));
		}
		for (String[] scene : OBJECT_EXPANSIONS.values())
		{
			for (String s : scene)
			{
				w.add(Names.normalize(s));
			}
		}
		OBJECT_WORDS = java.util.Collections.unmodifiableSet(w);
	}

	/**
	 * The scene-object names (normalized) a step's text asks for, e.g.
	 * "Climb the stairs" -&gt; {stairs, staircase}. Word-boundary matching on
	 * the lowercase text so "doorman" never matches "door"; a trailing
	 * plural 's' is tolerated ("climb the ladders").
	 */
	public static java.util.Set<String> objectWordsIn(String stepText)
	{
		if (stepText == null || stepText.isEmpty())
		{
			return java.util.Collections.emptySet();
		}
		String lower = stepText.toLowerCase(java.util.Locale.ROOT);
		java.util.Set<String> out = null;
		for (String word : OBJECT_WORD_LIST)
		{
			int idx = lower.indexOf(word);
			while (idx >= 0)
			{
				boolean startOk = idx == 0 || !Character.isLetter(lower.charAt(idx - 1));
				int end = idx + word.length();
				// allow a trailing plural 's' but no other letter continuation
				boolean endOk = end >= lower.length()
					|| !Character.isLetter(lower.charAt(end))
					|| (lower.charAt(end) == 's'
						&& (end + 1 >= lower.length() || !Character.isLetter(lower.charAt(end + 1))));
				if (startOk && endOk)
				{
					if (out == null)
					{
						out = new java.util.HashSet<>();
					}
					String[] scene = OBJECT_EXPANSIONS.get(word);
					if (scene != null)
					{
						for (String s : scene)
						{
							out.add(Names.normalize(s));
						}
					}
					else
					{
						out.add(Names.normalize(word));
					}
					break;
				}
				idx = lower.indexOf(word, idx + 1);
			}
		}
		return out == null ? java.util.Collections.emptySet() : out;
	}
}
