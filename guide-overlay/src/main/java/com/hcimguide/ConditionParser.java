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

	private ConditionParser()
	{
	}

	public static StepCondition parse(String text)
	{
		if (text == null)
		{
			return null;
		}

		List<ItemReq> items = ItemListParser.parse(text);
		if (items != null)
		{
			return StepCondition.itemsInInventory(items);
		}

		Matcher complete = COMPLETE_QUEST.matcher(text);
		if (complete.find())
		{
			Quest q = matchQuest(complete.group(1));
			if (q != null)
			{
				return StepCondition.questFinished(q);
			}
		}

		Matcher start = START_QUEST.matcher(text);
		if (start.find())
		{
			Quest q = matchQuest(start.group(1));
			if (q != null)
			{
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

	private static Quest matchQuest(String candidate)
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
				return q;
			}
		}
		// allow the candidate to carry trailing words, e.g. "Vampyre Slayer here"
		for (Quest q : Quest.values())
		{
			String name = normalize(q.getName());
			if (cand.startsWith(name) || cand.startsWith("the" + name)
				|| ("the" + cand).startsWith(name))
			{
				return q;
			}
		}
		return null;
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
