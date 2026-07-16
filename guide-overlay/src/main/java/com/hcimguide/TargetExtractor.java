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
}
