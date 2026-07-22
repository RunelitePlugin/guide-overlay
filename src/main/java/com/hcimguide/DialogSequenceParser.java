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
		String[] parts = m.group(1).split(",");
		int[] seq = new int[parts.length];
		for (int i = 0; i < parts.length; i++)
		{
			seq[i] = Integer.parseInt(parts[i].trim());
		}
		return seq;
	}
}
