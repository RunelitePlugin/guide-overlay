package com.hcimguide;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a dialogue-choice sequence from a step's text. Guides notate the
 * chat options to pick as "(2,1)" or "(3,1,2,2,1,1)": each number is the
 * option to click in the Nth option menu of the conversation.
 *
 * Deliberately strict to avoid false positives: two to twelve single digits
 * (1-9), commas only. Dose suffixes ("Prayer potion(4)") have one number,
 * coordinates ("(3212, 3428)") have multi-digit numbers, inventory notes
 * ("(14 Inventory Slots)") have words - none match.
 */
final class DialogSequenceParser
{
	private static final Pattern SEQ = Pattern.compile(
		"\\(\\s*([1-9]\\s*(?:,\\s*[1-9]\\s*){1,11})\\)");

	/**
	 * Trailing run of capitalized words (with optional of/the connectors
	 * between them) - guides write the choice notation directly after the
	 * NPC's name: "Talk to Father Aereck (3,1)", "on Veos (2,1)".
	 * Only ever run on a NAME_WINDOW-bounded suffix: on an unbounded
	 * capitalized run java.util.regex recurses per word and a hostile wiki
	 * edit of a few KB would StackOverflowError the guide import.
	 */
	private static final Pattern NAME_BEFORE = Pattern.compile(
		"([A-Z][A-Za-z'’-]*(?:\\s+(?:(?:of|the)\\s+)?[A-Z][A-Za-z'’-]*)*)\\s*$");

	/** The SEQ regex admits at most twelve entries, so this stays tiny. */
	private static final Pattern COMMA = Pattern.compile(",");

	/** Names max out at 32 chars; 48 leaves room for connectors. */
	private static final int NAME_WINDOW = 48;

	private DialogSequenceParser()
	{
	}

	/** First dialogue sequence in the text, or null when the step has none. */
	static int[] extract(String stepText)
	{
		if (stepText == null || stepText.isEmpty())
		{
			return null;
		}
		Matcher m = SEQ.matcher(stepText);
		if (!m.find())
		{
			return null;
		}
		String[] parts = COMMA.split(m.group(1));
		int[] seq = new int[parts.length];
		for (int i = 0; i < parts.length; i++)
		{
			seq[i] = Integer.parseInt(parts[i].trim());
		}
		return seq;
	}

	/**
	 * The name written directly before the first dialogue sequence, e.g.
	 * "Head East & start X Marks the Spot on Veos (2,1)" -&gt; "Veos". Guides
	 * put the notation right after whoever the conversation is with, which
	 * catches steps whose phrasing has no "Talk to" verb for
	 * {@link TargetExtractor#extract}. Underscores count as spaces
	 * ("Jennifer_(Shayzien)"). Null when nothing name-like precedes it.
	 */
	static String npcBefore(String stepText)
	{
		if (stepText == null || stepText.isEmpty())
		{
			return null;
		}
		Matcher m = SEQ.matcher(stepText);
		if (!m.find())
		{
			return null;
		}
		String before = stepText.substring(0, m.start()).replace('_', ' ').trim();
		if (before.length() > NAME_WINDOW)
		{
			// keep the regex input tiny; cut on a word boundary when one
			// exists so the window never starts mid-word
			int cut = before.length() - NAME_WINDOW;
			int ws = before.indexOf(' ', cut);
			before = ws >= 0 && ws < before.length() - 1
				? before.substring(ws + 1) : before.substring(cut);
		}
		Matcher n = NAME_BEFORE.matcher(before);
		if (!n.find())
		{
			return null;
		}
		String name = n.group(1).trim();
		if (name.length() < 2 || name.length() > 32)
		{
			return null;
		}
		return name;
	}
}
