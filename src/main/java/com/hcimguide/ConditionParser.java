package com.hcimguide;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * Derives an auto-completion condition from a step's text where one can be
 * verified against game state. Steps with no recognizable condition remain
 * manual-only.
 */
public final class ConditionParser
{
	private static final Pattern START_QUEST = Pattern.compile(
		"(?i)\\bstart(?:s|ed|ing)?\\s+([A-Za-z][A-Za-z'’ &-]{2,45})");
	private static final Pattern COMPLETE_QUEST = Pattern.compile(
		"(?i)\\bcomplete\\s+([A-Za-z][A-Za-z'’ &-]{2,45})");
	private static final Pattern SKILL_TARGET = Pattern.compile(
		"(?i)\\bto\\s+(\\d{1,2})\\s+([A-Za-z]+)\\b");
	/**
	 * "Start Biohazard until you get the samples": the step asks for progress
	 * INTO the quest, which merely having started it does not prove. The
	 * leading mark is the end of a quest name such as "Wanted!", which the
	 * name pattern itself does not capture.
	 */
	private static final Pattern PARTIAL_PROGRESS = Pattern.compile(
		"(?i)^[!?]?\\s*(?:quest\\s+)?(?:and\\s+continue\\s+(?:it\\s+)?)?(?:until|till|up\\s+to|upto)\\b");
	/**
	 * "Complete Ernest the Chicken & Vampyre Slayer": a second quest follows
	 * the matched one, and a condition can only watch one.
	 */
	private static final Pattern SECOND_QUEST = Pattern.compile("^[!?]?\\s*(?:&|\\+|and\\b)\\s*(?:the\\s+)?[A-Z]");
	/** Real steps stay under 900 characters; nothing longer gets a condition. */
	private static final int MAX_SCAN_CHARS = 1000;
	/**
	 * Longest row read as a quest instruction. Longer rows are advice
	 * paragraphs that merely mention a quest ("... there is a good chance you
	 * will finish this guide without 70 herblore to start Song of the Elves");
	 * instruction rows stay under this in both bundled guides.
	 */
	private static final int MAX_INSTRUCTION_CHARS = 400;

	private ConditionParser()
	{
	}

	public static StepCondition parse(String text)
	{
		if (text == null || text.length() > MAX_SCAN_CHARS)
		{
			return null;
		}

		List<ItemReq> items = ItemListParser.parse(text);
		if (items != null)
		{
			return StepCondition.itemsInInventory(items);
		}

		boolean instruction = text.length() <= MAX_INSTRUCTION_CHARS;
		int[] nameEnd = new int[1];
		Matcher complete = COMPLETE_QUEST.matcher(text);
		if (instruction && complete.find() && !isAside(text, complete.start()))
		{
			Quest q = matchQuest(complete.group(1), nameEnd);
			if (q != null)
			{
				if (SECOND_QUEST.matcher(text.substring(complete.start(1) + nameEnd[0])).find())
				{
					return null;
				}
				return StepCondition.questFinished(q);
			}
		}

		Matcher start = START_QUEST.matcher(text);
		if (instruction && start.find() && !isAside(text, start.start()))
		{
			Quest q = matchQuest(start.group(1), nameEnd);
			if (q != null)
			{
				String rest = text.substring(start.start(1) + nameEnd[0]);
				if (PARTIAL_PROGRESS.matcher(rest).find() || SECOND_QUEST.matcher(rest).find())
				{
					return null;
				}
				return StepCondition.questStarted(q);
			}
		}

		Matcher skill = SKILL_TARGET.matcher(text);
		if (skill.find())
		{
			Skill s = matchSkill(skill.group(2));
			if (s != null)
			{
				int level = Integer.parseInt(skill.group(1));
				if (level >= 1 && level <= 99)
				{
					return StepCondition.skillLevel(s, level);
				}
			}
		}

		return null;
	}

	/**
	 * True when a quest verb at {@code verbStart} sits inside a parenthetical
	 * aside ("(... it is advised to complete Perilous Moons before ...)") and
	 * so is not the step's own instruction.
	 */
	private static boolean isAside(String text, int verbStart)
	{
		int depth = 0;
		for (int i = 0; i < verbStart; i++)
		{
			char c = text.charAt(i);
			if (c == '(')
			{
				depth++;
			}
			else if (c == ')' && depth > 0)
			{
				depth--;
			}
		}
		return depth > 0;
	}

	/**
	 * @param nameEnd out: how many characters of {@code candidate} the quest
	 *                name itself covers, so the caller can inspect what follows
	 */
	private static Quest matchQuest(String candidate, int[] nameEnd)
	{
		String cand = normalize(candidate);
		if (cand.length() < 3)
		{
			return null;
		}
		for (Quest q : Quest.values())
		{
			String name = normalize(q.getName());
			if (name.equals(cand) || name.equals("the" + cand) || cand.equals("the" + name))
			{
				nameEnd[0] = candidate.length();
				return q;
			}
		}
		// allow the candidate to carry trailing words, e.g. "Vampyre Slayer
		// here". The LONGEST name wins: "Elemental Workshop II quest" must not
		// stop at "Elemental Workshop I".
		Quest best = null;
		int bestCovered = -1;
		for (Quest q : Quest.values())
		{
			String name = normalize(q.getName());
			int covered = cand.startsWith(name) ? name.length()
				: cand.startsWith("the" + name) ? name.length() + 3
				: ("the" + cand).startsWith(name) ? name.length() - 3 : -1;
			if (covered > bestCovered)
			{
				best = q;
				bestCovered = covered;
			}
		}
		if (best != null)
		{
			nameEnd[0] = rawLengthOf(candidate, bestCovered);
		}
		return best;
	}

	/** Length of the raw prefix that normalizes to {@code normalizedChars} characters. */
	private static int rawLengthOf(String raw, int normalizedChars)
	{
		int seen = 0;
		int i = 0;
		while (i < raw.length() && seen < normalizedChars)
		{
			if (Character.isLetterOrDigit(raw.charAt(i)))
			{
				seen++;
			}
			i++;
		}
		return i;
	}

	private static Skill matchSkill(String candidate)
	{
		for (Skill s : Skill.values())
		{
			if (s.getName().equalsIgnoreCase(candidate))
			{
				return s;
			}
		}
		return null;
	}

	private static String normalize(String s)
	{
		return Names.normalize(s);
	}
}
