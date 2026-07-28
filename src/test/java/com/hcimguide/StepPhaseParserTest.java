package com.hcimguide;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StepPhaseParserTest
{
	@Test
	public void separatesTravelFromARealFollowUpTask()
	{
		assertPlan("Teleport to Varrock and talk to Aubury",
			StepPhaseParser.Mode.TRAVEL_THEN_TASK,
			StepPhaseParser.Kind.TRAVEL, StepPhaseParser.Kind.TASK);
		assertPlan("Head North and take Jug of Water",
			StepPhaseParser.Mode.TRAVEL_THEN_TASK,
			StepPhaseParser.Kind.TRAVEL, StepPhaseParser.Kind.TASK);
		assertPlan("Head to Ice Mountain. There use Secateurs on White Tree",
			StepPhaseParser.Mode.TRAVEL_THEN_TASK,
			StepPhaseParser.Kind.TRAVEL, StepPhaseParser.Kind.TASK);
	}

	@Test
	public void recognizesConveyancesWithoutTreatingThemAsItems()
	{
		assertMode("Take the Arceuus Minecart to Lovakenj",
			StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Head West and take the Lady of the Waves to Port Khazard",
			StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Dueling ring to Al Kharid, take the gnome glider to Feldip Hills",
			StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Return to Minecart and take to Shayzien East",
			StepPhaseParser.Mode.PURE_TRAVEL);
	}

	@Test
	public void preservesFairyRingAccessAndCodeAsOneExecutableRoute()
	{
		StepPhaseParser.Parsed parsed = StepPhaseParser.parse("Ardy Cloak -> AIS");
		assertEquals(StepPhaseParser.Mode.PURE_TRAVEL, parsed.getMode());
		assertEquals(1, parsed.getClauses().size());
		assertEquals("Ardy Cloak -> AIS", parsed.getClauses().get(0).getText());
	}

	@Test
	public void arrowToNonTravelTaskBecomesASeparatePhase()
	{
		assertPlan("Teleport to POH -> Lunar Spellbook",
			StepPhaseParser.Mode.TRAVEL_THEN_TASK,
			StepPhaseParser.Kind.TRAVEL, StepPhaseParser.Kind.TASK);
	}

	@Test
	public void destinationNamedBankIsNotAVisitToThenUseTheBank()
	{
		assertMode("Head to Edgeville Bank", StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Run to Falador east bank", StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Teleport to Ardougne and bank at South Bank",
			StepPhaseParser.Mode.TRAVEL_THEN_TASK);
		assertMode("Teleport to a bank to prepare Barrows",
			StepPhaseParser.Mode.MANUAL);
	}

	@Test
	public void itemSuffixCreatesPreparationWithoutChangingTravelClassification()
	{
		StepPhaseParser.Parsed parsed = StepPhaseParser.parse(
			"Dueling ring to Ferox, take the portal to Soul Wars, then to Edgeville. "
				+ "(Items: 1600 soda ash, chisel)");
		assertTrue(parsed.hasItemPreparation());
		assertEquals(StepPhaseParser.Mode.PURE_TRAVEL, parsed.getMode());
	}

	@Test
	public void optionalAndAlternativeRoutesFailClosed()
	{
		assertMode("Use a games necklace to Games Room or Minigame Teleport",
			StepPhaseParser.Mode.MANUAL);
		assertMode("Teleport to Trollheim if you can, else Ardy Cloak -> AJR",
			StepPhaseParser.Mode.MANUAL);
		assertMode("Whenever convenient, teleport to Canifis",
			StepPhaseParser.Mode.MANUAL);
	}

	@Test
	public void leadingTaskOrTaskBetweenTravelLegsBecomesGuidanceOnly()
	{
		// These rows produce phases for TARGETING only. Arrival completion is
		// gated separately on the mode, so they still cannot auto-complete -
		// guidanceOnlyRowsCannotComplete() below pins that property.
		assertMode("Right click House Teleport, choose Outside House, then head east",
			StepPhaseParser.Mode.GUIDANCE_ONLY);
		assertMode("Teleport to POH, send the butler, then teleport to Digsite",
			StepPhaseParser.Mode.GUIDANCE_ONLY);
		assertMode("Dump all your herblore experience, then return to this step",
			StepPhaseParser.Mode.GUIDANCE_ONLY);
	}

	@Test
	public void parentheticalRequiredActionPreventsArrivalCompletion()
	{
		StepPhaseParser.Parsed parsed = StepPhaseParser.parse(
			"Go to Edgeville, take the lever to the Wilderness, then Ardougne "
				+ "(use the lever 4 times)");
		assertTrue(parsed.hasRequiredParentheticalAction());
		assertEquals(StepPhaseParser.Mode.MANUAL, parsed.getMode());
	}

	@Test
	public void optionalParentheticalDoesNotCreateAFalseTask()
	{
		StepPhaseParser.Parsed parsed = StepPhaseParser.parse(
			"Use your chronicle to teleport to Varrock "
				+ "(optional: restore run energy at Clan Wars first). (Items: bucket)");
		assertFalse(parsed.hasRequiredParentheticalAction());
		assertEquals(StepPhaseParser.Mode.PURE_TRAVEL, parsed.getMode());
	}

	@Test
	public void unknownRouteFragmentIsNotSilentlyDiscarded()
	{
		assertMode("Falador Teleport -> 10 Grain south of Doric -> Tegid",
			StepPhaseParser.Mode.MANUAL);
		assertMode("Teleport to Falador -> somewhere mysterious", StepPhaseParser.Mode.MANUAL);
	}

	@Test
	public void commonDirectionAndAcronymTextRemainIntact()
	{
		assertMode("Head North then East", StepPhaseParser.Mode.PURE_TRAVEL);
		StepPhaseParser.Parsed acronym = StepPhaseParser.parse("Head south to H.A.M. Hideout");
		assertEquals(1, acronym.getClauses().size());
		assertEquals("Head south to H.A.M. Hideout", acronym.getClauses().get(0).getText());
	}

	@Test
	public void conditionalStartingLocationRemainsManual()
	{
		assertMode("If you were at Land's End, now charter a ship to Catherby",
			StepPhaseParser.Mode.MANUAL);
		assertMode("If you were at Port Sarim, charter to the Pandemonium",
			StepPhaseParser.Mode.MANUAL);
	}

	@Test
	public void fairyRingAccessDoesNotReplaceAnExplicitFinalDestination()
	{
		assertPlan("Ardy Cloak -> CLS Fairy Ring to get to Hazelmere",
			StepPhaseParser.Mode.PURE_TRAVEL,
			StepPhaseParser.Kind.TRAVEL, StepPhaseParser.Kind.TRAVEL);
	}

	@Test
	public void recognizesUseBasedTransportAndTemporalContext()
	{
		assertMode("Use your freshly charged Glory to teleport to Edgeville",
			StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Use 2 ectotokens to enter Port Phasmatys",
			StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("After Finding Percival use Fairy Ring BJR to return",
			StepPhaseParser.Mode.PURE_TRAVEL);
	}

	@Test
	public void placeNamesAndLocationExitDoNotBecomeTasks()
	{
		assertMode("Return to Eagles Peak and use Rope on Eagle to Snowy Hunter Area",
			StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Leave the Tzhaar cave", StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Leave one redberry in the bank", StepPhaseParser.Mode.MANUAL);
	}

	@Test
	public void arrowConveyancesRetainAllTravelLegs()
	{
		assertMode("Minecart -> Shayzien East", StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Glider -> Feldip Hills -> Back to Al Kharid",
			StepPhaseParser.Mode.PURE_TRAVEL);
		assertMode("Ardy Cloak -> CLS Fairy Ring to get to Hazelmere",
			StepPhaseParser.Mode.PURE_TRAVEL);
	}

	private static void assertMode(String text, StepPhaseParser.Mode expected)
	{
		assertEquals(text, expected, StepPhaseParser.parse(text).getMode());
	}

	private static void assertPlan(String text, StepPhaseParser.Mode mode,
		StepPhaseParser.Kind... kinds)
	{
		StepPhaseParser.Parsed parsed = StepPhaseParser.parse(text);
		assertEquals(text, mode, parsed.getMode());
		List<StepPhaseParser.Kind> actual = parsed.getClauses().stream()
			.filter(c -> c.getKind() != StepPhaseParser.Kind.NOTE)
			.map(StepPhaseParser.Clause::getKind)
			.collect(Collectors.toList());
		assertEquals(text, Arrays.asList(kinds), actual);
	}

	/** The safety property: guidance-only rows may target but never complete. */
	@Test
	public void guidanceOnlyRowsCannotComplete()
	{
		String[] rows = {
			"Right click House Teleport, choose Outside House, then head east",
			"Teleport to POH, send the butler, then teleport to Digsite",
			"Dump all your herblore experience, then return to this step",
		};
		for (String row : rows)
		{
			assertEquals(row, StepPhaseParser.Mode.GUIDANCE_ONLY,
				StepPhaseParser.parse(row).getMode());
			assertFalse(row, ArrivalCompletionPolicy.isPureTravelText(row));
		}
	}
}
