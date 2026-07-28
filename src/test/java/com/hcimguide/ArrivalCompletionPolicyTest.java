package com.hcimguide;

import java.util.Collections;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArrivalCompletionPolicyTest
{
	private static final String BANK = "B1";

	@Test
	public void explicitTeleportCanCompleteAtFinalDestination()
	{
		GuideStep step = step("Teleport to Varrock");
		assertTrue(ArrivalCompletionPolicy.canComplete(step,
			plan(step, transport("Varrock", 3213, 3424)), false, false, false));
	}

	@Test
	public void explicitWalkingStepCanCompleteAtNamedPlace()
	{
		GuideStep step = step("Run to Canifis");
		assertTrue(ArrivalCompletionPolicy.canComplete(step,
			plan(step, place("Canifis", 3505, 3485)), false, false, false));
	}

	@Test
	public void commonMovementPhrasesRemainEligible()
	{
		GuideStep makeWay = step("Make your way to Canifis");
		assertTrue(ArrivalCompletionPolicy.canComplete(makeWay,
			plan(makeWay, place("Canifis", 3505, 3485)), false, false, false));
		GuideStep climb = step("Climb down to the marked cave entrance");
		assertTrue(ArrivalCompletionPolicy.canComplete(climb,
			plan(climb, place("Cave entrance", 3200, 3200)), false, false, false));
		GuideStep compoundMake = step("Go to Varrock and make a pie");
		assertFalse(ArrivalCompletionPolicy.canComplete(compoundMake,
			plan(compoundMake, place("Varrock", 3213, 3424)), false, false, false));
	}

	@Test
	public void compoundTeleportAndActionDoesNotCompleteFromArrival()
	{
		GuideStep step = step("Salve teleport, kill ghouls for the diary");
		assertFalse(ArrivalCompletionPolicy.canComplete(step,
			plan(step, transport("Salve Graveyard", 3432, 3460)), false, false, false));
	}

	@Test
	public void separateConditionEntityParentAndInheritedLocationsAreRejected()
	{
		GuideStep step = step("Run to Canifis");
		StepLocationPlan direct = plan(step, place("Canifis", 3505, 3485));
		assertFalse(ArrivalCompletionPolicy.canComplete(step, direct, true, false, false));
		assertFalse(ArrivalCompletionPolicy.canComplete(step, direct, false, true, false));
		assertFalse(ArrivalCompletionPolicy.canComplete(step, direct, false, false, true));

		StepLocationHint inherited = place("Canifis", 3505, 3485).inferredCopy();
		assertFalse(ArrivalCompletionPolicy.canComplete(step, plan(step, inherited),
			false, false, false));
	}

	@Test
	public void finalWaypointControlsCompletionForMultiStopTravel()
	{
		GuideStep step = step("Ring of dueling to Emir's Arena, then run to Mage Training Arena");
		StepLocationPlan plan = new StepLocationPlan(step.getKey(), java.util.Arrays.asList(
			transport("Emir's Arena", 3315, 3235), place("Mage Training Arena", 3363, 3317)));
		assertTrue(ArrivalCompletionPolicy.canComplete(step, plan, false, false, false));
	}

	@Test
	public void destinationNamedBankIsStillPureTravel()
	{
		GuideStep step = step("Teleport to Varrock East Bank");
		assertTrue(ArrivalCompletionPolicy.canComplete(step,
			plan(step, place("Varrock East Bank", 3253, 3420)), false, false, false));
	}

	@Test
	public void sequencedBankAndObjectActionsAreRejected()
	{
		GuideStep bank = step("Teleport to Varrock, then bank");
		assertFalse(ArrivalCompletionPolicy.canComplete(bank,
			plan(bank, transport("Varrock", 3213, 3424)), false, false, false));
		GuideStep open = step("Teleport to Lumbridge and open the chest");
		assertFalse(ArrivalCompletionPolicy.canComplete(open,
			plan(open, transport("Lumbridge", 3222, 3218)), false, false, false));
	}

	@Test
	public void unresolvedFinalTravelNeverCompletesAtIntermediateWaypoint()
	{
		GuideStep step = step("Teleport to Lumbridge, then run to an unknown cellar");
		StepLocationPlan incomplete = new StepLocationPlan(step.getKey(),
			Collections.singletonList(transport("Lumbridge", 3222, 3218)),
			LocationResolutionReason.UNKNOWN_NAMED_PLACE,
			Collections.singletonList("run to an unknown cellar"));
		assertFalse(ArrivalCompletionPolicy.canComplete(step, incomplete,
			false, false, false));
	}

	@Test
	public void transportShorthandCanCompleteWithoutGenericTravelVerb()
	{
		GuideStep step = step("Dueling ring to Castle Wars");
		assertTrue(ArrivalCompletionPolicy.canComplete(step,
			plan(step, transport("Castle Wars", 2440, 3089)), false, false, false));
	}

	@Test
	public void visitOnlyNamedPlaceCanCompleteButSequencedVisitCannot()
	{
		GuideStep direct = step("Visit Canifis");
		assertTrue(ArrivalCompletionPolicy.canComplete(direct,
			plan(direct, place("Canifis", 3505, 3485)), false, false, false));
		GuideStep compound = step("Teleport to Varrock and visit the museum");
		assertFalse(ArrivalCompletionPolicy.canComplete(compound,
			plan(compound, transport("Varrock", 3213, 3424)), false, false, false));
	}

	@Test
	public void rejectsAdditionalCompoundActions()
	{
		GuideStep read = step("Teleport to Varrock and read the noticeboard");
		assertFalse(ArrivalCompletionPolicy.canComplete(read,
			plan(read, transport("Varrock", 3213, 3424)), false, false, false));
		GuideStep pay = step("Run to Canifis and pay the boatman");
		assertFalse(ArrivalCompletionPolicy.canComplete(pay,
			plan(pay, place("Canifis", 3505, 3485)), false, false, false));
		GuideStep open = step("Open the gate at Canifis");
		assertFalse(ArrivalCompletionPolicy.canComplete(open,
			plan(open, place("Canifis", 3505, 3485)), false, false, false));
	}

	@Test
	public void manualPinDoesNotCreateTravelSemantics()
	{
		StepLocationHint manual = StepLocationHint.manual("manual:test", "Custom point",
			new WorldPoint(3200, 3200, 0), 5);
		GuideStep wait = step("Wait here for the next instruction");
		assertFalse(ArrivalCompletionPolicy.canComplete(wait,
			plan(wait, manual), false, false, false));
		GuideStep walk = step("Walk to the marked point");
		assertTrue(ArrivalCompletionPolicy.canComplete(walk,
			plan(walk, manual), false, false, false));
	}

	private static GuideStep step(String text)
	{
		return new GuideStep(BANK + "#step", text, 0, BANK);
	}

	private static StepLocationPlan plan(GuideStep step, StepLocationHint hint)
	{
		return new StepLocationPlan(step.getKey(), Collections.singletonList(hint));
	}

	private static StepLocationHint transport(String label, int x, int y)
	{
		return new StepLocationHint(label, new WorldPoint(x, y, 0), false, true, true,
			"transport:" + label.toLowerCase().replace(' ', '-'), LocationSource.TRANSPORT,
			LocationConfidence.EXACT, 5, 2);
	}

	private static StepLocationHint place(String label, int x, int y)
	{
		return new StepLocationHint(label, new WorldPoint(x, y, 0), false, true, false,
			"place:" + label.toLowerCase().replace(' ', '-'), LocationSource.NAMED_PLACE,
			LocationConfidence.EXACT, 5, 2);
	}
}
