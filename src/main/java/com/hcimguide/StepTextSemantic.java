package com.hcimguide;

import java.util.regex.Pattern;

/**
 * High-signal semantic categories used only for step text presentation.
 *
 * <p>The matcher is intentionally conservative. It highlights explicit danger,
 * preparation/setup instructions, and transport actions without treating every
 * boss name, item name, level goal, or three-letter token as special.</p>
 */
final class StepTextSemantic
{
	enum Kind
	{
		NORMAL,
		TRANSPORT,
		PREPARATION,
		DANGER
	}

	private static final Pattern DANGER = Pattern.compile(
		"(?i)\\b(?:wilderness|wildy|suicid(?:e|ing)|die|dying|died|kill yourself|"
			+ "intentional(?:ly)? (?:die|death)|(?:safe|unsafe) death|death pile|death storage|"
			+ "lost on death|lose[^.\\n]{0,40} on death|items?[^.\\n]{0,40} lost on death|"
			+ "pk(?:er|ing)?|player killer|danger(?:ous)?|hardcore warning|hcim warning|"
			+ "one[- ]life|risk(?:ing)? (?:your )?(?:items?|gear)|irreversible|cannot be undone)\\b");

	/** Preparation verbs at the start of the actionable instruction. */
	private static final Pattern PREPARATION_LEAD = Pattern.compile(
		"(?i)^\\s*(?:withdraw|bring|equip|wear|wield|bank|deposit|buy|purchase|"
			+ "charge|recharge|fill|stock up|prepare|set up|grab)\\b");

	/** Strong setup/prerequisite language takes priority over a later travel verb. */
	private static final Pattern PREPARATION_REQUIREMENT = Pattern.compile(
		"(?i)\\b(?:make sure(?: you)? have|ensure(?: you)? have|must have|requires?|requirements?|"
			+ "minimum(?: level)?|at least (?:level )?\\d{1,3}|"
			+ "before (?:leaving|starting|entering|teleporting|travelling|traveling)|"
			+ "bring [^.\\n]{1,80} with you)\\b");

	/** Weaker setup details yield to an actual transport action in the same step. */
	private static final Pattern PREPARATION_DETAIL = Pattern.compile(
		"(?i)\\b(?:\\d+ charges?|fully charged|partially charged|"
			+ "in (?:your )?inventory|with [^.\\n]{1,80} equipped)\\b");

	private StepTextSemantic()
	{
	}

	static Kind classify(String stepText)
	{
		if (stepText == null || stepText.trim().isEmpty())
		{
			return Kind.NORMAL;
		}
		if (DANGER.matcher(stepText).find())
		{
			return Kind.DANGER;
		}
		// A setup instruction mentioning a later teleport is still preparation,
		// e.g. "Withdraw food before teleporting" should be amber, not cyan.
		if (PREPARATION_LEAD.matcher(stepText).find()
			|| PREPARATION_REQUIREMENT.matcher(stepText).find())
		{
			return Kind.PREPARATION;
		}
		if (TransportResolver.hasTransportIntent(stepText))
		{
			return Kind.TRANSPORT;
		}
		if (PREPARATION_DETAIL.matcher(stepText).find())
		{
			return Kind.PREPARATION;
		}
		return Kind.NORMAL;
	}
}
