package com.hcimguide;

import net.runelite.api.Quest;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Auto-completion conditions, NPC targets and dialogue notes read from step text. */
public class StepSemanticsFixesTest
{
	// ---- quest conditions ---------------------------------------------------

	@Test
	public void startingAQuestDoesNotProveProgressIntoIt()
	{
		assertNull(ConditionParser.parse("Start Biohazard until you get the samples"));
		assertNull(ConditionParser.parse("Start Dwarf Cannon up to needing to go to Falador"));
		assertNull(ConditionParser.parse("Start the Golem until you have strange implement."));
		assertNull(ConditionParser.parse("Start Wanted! and continue it until you have to go to Taverley dungeon."));

		StepCondition plain = ConditionParser.parse("Start Biohazard [Side with Elena]");
		assertEquals(StepCondition.Type.QUEST_STARTED, plain.getType());
		assertEquals(Quest.BIOHAZARD, plain.getQuest());
		assertNotNull(ConditionParser.parse("Head East & Start X Marks the Spot on Veos (2,1)"));
	}

	@Test
	public void oneConditionCannotVouchForTwoQuests()
	{
		assertNull(ConditionParser.parse("Complete Ernest the Chicken & Vampyre Slayer when getting the skull"));
		assertNull(ConditionParser.parse("Complete Rum Deal & Cabin Fever once 58 Slayer after Defender"));
		// an ampersand inside the quest's own name is not a second quest
		assertEquals(Quest.ROMEO__JULIET, ConditionParser.parse("Talk to Romeo to complete Romeo & Juliet").getQuest());
		assertEquals(Quest.ERNEST_THE_CHICKEN,
			ConditionParser.parse("Complete Ernest the Chicken and bank the items").getQuest());
	}

	@Test
	public void aQuestMentionedInAnAsideOrAnAdviceParagraphIsNotTheInstruction()
	{
		assertNull(ConditionParser.parse("Train on Sulphur Naguas until 60 Attack (it is advised to complete "
			+ "Perilous Moons before AFKing them)"));

		StringBuilder advice = new StringBuilder("AT THIS POINT YOU SHOULD PRIORITISE CONTRACTS. ");
		while (advice.length() < 420)
		{
			advice.append("Herb runs are mandatory and worth doing whenever they are ready. ");
		}
		advice.append("You may finish without 70 herblore to start Song of the Elves");
		assertNull(ConditionParser.parse(advice.toString()));
	}

	@Test
	public void theLongestQuestNameWins()
	{
		assertEquals(Quest.ELEMENTAL_WORKSHOP_II,
			ConditionParser.parse("Complete Elemental Workshop II quest").getQuest());
		assertEquals(Quest.ELEMENTAL_WORKSHOP_I,
			ConditionParser.parse("Complete Elemental Workshop I quest").getQuest());
		assertEquals(Quest.MONKEY_MADNESS_II, ConditionParser.parse("Start Monkey Madness II now").getQuest());
	}

	// ---- NPC targets --------------------------------------------------------

	@Test
	public void anArticleIsNotPartOfTheName()
	{
		assertEquals("Chicken", TargetExtractor.extract("Kill a Chicken. Take Everything"));
		assertEquals("cow", TargetExtractor.extract("Kill a cow & take everything"));
		assertEquals("Ice Fiend", TargetExtractor.extract("Kill an Ice Fiend [Falador Medium Diary]"));
		assertEquals("man", TargetExtractor.extract("Pickpocket a man/woman [Lumbridge Easy Diary]"));
		assertEquals("boy", TargetExtractor.extract("Head to Taverley and talk to the boy"));
		assertEquals("Anita", TargetExtractor.extract("Talk to Anita upstairs"));
		assertTrue(TargetExtractor.namesMatch(TargetExtractor.extract("Kill a Chicken"), "Chicken"));
	}

	@Test
	public void proseAfterTheVerbIsNotAName()
	{
		assertNull(TargetExtractor.extract("They can kill you if you do not pay attention"));
		assertNull(TargetExtractor.extract("Kill it quickly"));
		assertNull(TargetExtractor.extract("Kill all of them"));
		assertEquals("Sandy", TargetExtractor.extract("Talk to Sandy until you get sand"));
		assertEquals("Harold", TargetExtractor.extract("Speak to Harold upstairs in Burthope bar"));
		assertEquals("Queen Ellamaria", TargetExtractor.extract("Talk to Queen Ellamaria twice"));
		assertEquals("Lady of the Lake", TargetExtractor.extract("Talk to the Lady of the Lake by the shore"));
	}

	// ---- dialogue notes -----------------------------------------------------

	@Test
	public void aSingleChoiceIsADialogueNote()
	{
		assertArrayEquals(new int[]{1}, DialogSequenceParser.extract("Talk to Morgan and start Vampyre Slayer (1)"));
		assertEquals("Vampyre Slayer", DialogSequenceParser.npcBefore("Talk to Morgan and start Vampyre Slayer (1)"));
		assertArrayEquals(new int[]{3}, DialogSequenceParser.extract("Talk to Gillie Groats (3) [Ides of Milk]"));
		assertEquals("Gillie Groats", DialogSequenceParser.npcBefore("Talk to Gillie Groats (3) [Ides of Milk]"));
		assertArrayEquals(new int[]{7}, DialogSequenceParser.extract("Take Minecart to Lovakengj (7)"));
		assertArrayEquals(new int[]{1}, DialogSequenceParser.extract("Search for book and talk to Reldo again (1)"));
		// a full sequence anywhere in the step still wins
		assertArrayEquals(new int[]{2, 1},
			DialogSequenceParser.extract("Talk to Bob (1) then to Veos (2,1)"));
	}

	@Test
	public void dosesChargesAndTiersAreNotChoices()
	{
		String[] notNotes = {
			"Withdraw: Coins, Prayer potion(4), Waterskin (4)",
			"Make 2 Agility Potions (3). Decant into (4)",
			"Decant the potions into 3 (4)",
			"Withdraw: 3x Duelling Ring (8), 3x games necklace (8)",
			"Withdraw: Rada's Blessing (2), House Teleport",
			"buy 16 relicym's balm (3), decant them",
			"grab superantipoisons (84 doses - 21 pots (4)-dose) by hopping",
			"Wear a magic shortbow (i), shayzien boots (5).",
			"PUT THE EXPERIENCE IN RANGING (27)",
			"Holy Sickle (b)",
		};
		for (String text : notNotes)
		{
			assertNull(text, DialogSequenceParser.extract(text));
			assertNull(text, DialogSequenceParser.npcBefore(text));
		}
	}
}
