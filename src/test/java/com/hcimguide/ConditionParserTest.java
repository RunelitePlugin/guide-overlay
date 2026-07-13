package com.hcimguide;

import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ConditionParserTest
{
	@Test
	public void detectsQuestStart()
	{
		StepCondition c = ConditionParser.parse("Talk to Morgan and start Vampyre Slayer (1)");
		assertEquals(StepCondition.Type.QUEST_STARTED, c.getType());
		assertEquals(Quest.VAMPYRE_SLAYER, c.getQuest());

		c = ConditionParser.parse("Head to King Roald and start Priest in Peril (1,1,3)");
		assertEquals(StepCondition.Type.QUEST_STARTED, c.getType());
		assertEquals(Quest.PRIEST_IN_PERIL, c.getQuest());

		c = ConditionParser.parse("Start A Porcine of Interest");
		assertEquals(StepCondition.Type.QUEST_STARTED, c.getType());
		assertEquals(Quest.A_PORCINE_OF_INTEREST, c.getQuest());
	}

	@Test
	public void detectsQuestCompletion()
	{
		StepCondition c = ConditionParser.parse("Head West to Port Sarim and talk to Veos to complete X Marks The Spot.");
		assertEquals(StepCondition.Type.QUEST_FINISHED, c.getType());
		assertEquals(Quest.X_MARKS_THE_SPOT, c.getQuest());
	}

	@Test
	public void ignoresPartialQuestSteps()
	{
		// "Complete 2nd Step of ..." must NOT auto-complete on quest finish
		assertNull(ConditionParser.parse("Complete 2nd Step of X Marks The Spot"));
		// not a quest at all
		assertNull(ConditionParser.parse("Complete 3 Floors of Stronghold of Security."));
	}

	@Test
	public void detectsSkillTargets()
	{
		StepCondition c = ConditionParser.parse("Train Draynor Agility to 5 Agility [Lumbridge Easy Diary]");
		assertEquals(StepCondition.Type.SKILL_LEVEL, c.getType());
		assertEquals(Skill.AGILITY, c.getSkill());
		assertEquals(5, c.getLevel());
	}

	@Test
	public void detectsItemSteps()
	{
		StepCondition c = ConditionParser.parse("Withdraw: Coins, Spade, Tinderbox (3 Inventory slots)");
		assertEquals(StepCondition.Type.ITEMS_IN_INVENTORY, c.getType());
		assertEquals(3, c.getItems().size());
	}

	@Test
	public void plainStepsHaveNoCondition()
	{
		assertNull(ConditionParser.parse("Head West towards Draynor hugging the fence to avoid the Jail Guards"));
		assertNull(ConditionParser.parse("Talk to Father Aereck (3,1) [Restless Ghost]"));
	}
}
