package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class StepTextSemanticTest
{
	@Test
	public void classifiesActualTransportAsCyanCategory()
	{
		assertEquals(StepTextSemantic.Kind.TRANSPORT,
			StepTextSemantic.classify("Use your games necklace to teleport to Wintertodt"));
		assertEquals(StepTextSemantic.Kind.TRANSPORT,
			StepTextSemantic.classify("Fairy ring -> BKR, then run east"));
		assertEquals(StepTextSemantic.Kind.TRANSPORT,
			StepTextSemantic.classify("Take the Quetzal to Tal Teklan"));
	}

	@Test
	public void classifiesPreparationWithoutMistakingTeleportItemsForTravel()
	{
		assertEquals(StepTextSemantic.Kind.PREPARATION,
			StepTextSemantic.classify("Withdraw a games necklace, food, and stamina potion"));
		assertEquals(StepTextSemantic.Kind.PREPARATION,
			StepTextSemantic.classify("Equip your Ardougne cloak before leaving"));
		assertEquals(StepTextSemantic.Kind.PREPARATION,
			StepTextSemantic.classify("Requires at least level 62 Farming"));
	}

	@Test
	public void preparationLeadWinsOverLaterTransportMention()
	{
		assertEquals(StepTextSemantic.Kind.PREPARATION,
			StepTextSemantic.classify("Withdraw food before teleporting to the Digsite"));
	}

	@Test
	public void explicitRequirementWinsOverLaterTransportMention()
	{
		assertEquals(StepTextSemantic.Kind.PREPARATION,
			StepTextSemantic.classify("Make sure you have food before teleporting to Varrock"));
		assertEquals(StepTextSemantic.Kind.TRANSPORT,
			StepTextSemantic.classify("Teleport to Varrock with 3 charges remaining"));
	}

	@Test
	public void dangerWinsOverTransportAndPreparation()
	{
		assertEquals(StepTextSemantic.Kind.DANGER,
			StepTextSemantic.classify("Teleport to the Wilderness and do not die"));
		assertEquals(StepTextSemantic.Kind.DANGER,
			StepTextSemantic.classify("Bring only items you are willing to lose on death"));
		assertEquals(StepTextSemantic.Kind.DANGER,
			StepTextSemantic.classify("Suicide to return faster"));
	}

	@Test
	public void avoidsCommonFalsePositives()
	{
		assertEquals(StepTextSemantic.Kind.PREPARATION,
			StepTextSemantic.classify("Buy death runes from the shop"));
		assertEquals(StepTextSemantic.Kind.NORMAL,
			StepTextSemantic.classify("Complete Death Plateau"));
		assertEquals(StepTextSemantic.Kind.NORMAL,
			StepTextSemantic.classify("Train Farming to level 62"));
		assertEquals(StepTextSemantic.Kind.NORMAL,
			StepTextSemantic.classify("Kill the boss and loot the chest"));
	}
}
