package com.hcimguide;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StepLocationPlannerTest
{
	private final PlaceDirectory places = new PlaceDirectory(new Gson());
	private final TransportResolver transports = new TransportResolver(new Gson());

	@Test
	public void directPlaceAndLocalActionsShareAnArea()
	{
		GuideBank bank = bank("B1",
			step("a", "Do Tithe Farm until 62 Farming", "B1"),
			step("b", "Buy the seed box and refill your watering cans", "B1"));
		Map<String, StepLocationHint> hints = StepLocationPlanner.build(
			guide(bank), Collections.emptyMap(), places, transports, name -> null);

		assertEquals("Tithe Farm", hints.get("a").getLabel());
		assertFalse(hints.get("a").isInferred());
		assertEquals("Tithe Farm", hints.get("b").getLabel());
		assertTrue(hints.get("b").isInferred());
	}

	@Test
	public void contextNeverCrossesABankBoundary()
	{
		GuideBank first = bank("B1", step("a", "Do Tithe Farm", "B1"));
		GuideBank second = bank("B2", step("b", "Withdraw coins and a hammer", "B2"));
		Map<String, StepLocationHint> hints = StepLocationPlanner.build(
			guide(first, second), Collections.emptyMap(), places, transports, name -> null);

		assertTrue(hints.containsKey("a"));
		assertFalse(hints.containsKey("b"));
	}

	@Test
	public void unresolvedMovementBreaksInheritedContext()
	{
		GuideBank bank = bank("B1",
			step("a", "Do Tithe Farm", "B1"),
			step("b", "Walk to the unknown shrine", "B1"),
			step("c", "Buy three ropes", "B1"));
		Map<String, StepLocationHint> hints = StepLocationPlanner.build(
			guide(bank), Collections.emptyMap(), places, transports, name -> null);

		assertTrue(hints.containsKey("a"));
		assertFalse(hints.containsKey("b"));
		assertFalse(hints.containsKey("c"));
	}

	@Test
	public void movementPlaceOverridesEntityMentionedEnRoute()
	{
		GuideBank bank = bank("B1", step("a", "Go to Tithe Farm and talk to the farmer", "B1"));
		Map<String, String> targets = new HashMap<>();
		targets.put("a", "Farmer");
		Map<String, StepLocationHint> hints = StepLocationPlanner.build(
			guide(bank), targets, places, transports, name -> new WorldPoint(3222, 3218, 0));

		assertEquals("Tithe Farm", hints.get("a").getLabel());
		assertTrue(hints.get("a").isPreferredOverEntity());
	}

	@Test
	public void ordinaryInteractionPrefersPreciseEntityLocation()
	{
		GuideBank bank = bank("B1", step("a", "Talk to the farmer at Tithe Farm", "B1"));
		Map<String, String> targets = new HashMap<>();
		targets.put("a", "Farmer");
		WorldPoint npcPoint = new WorldPoint(1805, 3502, 0);
		Map<String, StepLocationHint> hints = StepLocationPlanner.build(
			guide(bank), targets, places, transports, name -> npcPoint);

		assertEquals("Farmer", hints.get("a").getLabel());
		assertEquals(npcPoint, hints.get("a").getPoint());
		assertFalse(hints.get("a").isPreferredOverEntity());
	}

	@Test
	public void fairyRingCodeResolvesBeforeOrdinaryPlaceMatching()
	{
		GuideBank bank = bank("B1", step("a", "Ardy cloak -> CIS, then run north", "B1"));
		Map<String, StepLocationHint> hints = StepLocationPlanner.build(
			guide(bank), Collections.emptyMap(), places, transports, name -> null);

		assertEquals("Fairy ring CIS", hints.get("a").getLabel());
		assertEquals(new WorldPoint(1636, 3869, 0), hints.get("a").getPoint());
		assertTrue(hints.get("a").isTransport());
	}

	@Test
	public void genericTransportToKnownPlaceIsMarkedAsTransport()
	{
		GuideBank bank = bank("B1", step("a", "Take the minecart to Lovakengj", "B1"));
		Map<String, StepLocationHint> hints = StepLocationPlanner.build(
			guide(bank), Collections.emptyMap(), places, transports, name -> null);

		assertEquals("Lovakengj", hints.get("a").getLabel());
		assertTrue(hints.get("a").isTransport());
		assertTrue(hints.get("a").isPreferredOverEntity());
	}

	@Test
	public void laterNamedEndpointBeatsIntermediateTeleportArrival()
	{
		GuideBank bank = bank("B1", step("a",
			"Ring of dueling to Emir's Arena, then run to Mage Training Arena", "B1"));
		Map<String, StepLocationHint> hints = StepLocationPlanner.build(
			guide(bank), Collections.emptyMap(), places, transports, name -> null);

		assertEquals("Mage Training Arena", hints.get("a").getLabel());
		assertEquals(new WorldPoint(3363, 3317, 0), hints.get("a").getPoint());
		assertTrue(hints.get("a").isTransport());
	}

	@Test
	public void multiStopInstructionBuildsOrderedWaypoints()
	{
		GuideBank bank = bank("B1", step("a",
			"Use a ring of dueling to Emir's Arena, then run to Mage Training Arena", "B1"));
		Map<String, StepLocationPlan> plans = StepLocationPlanner.buildPlans(
			guide(bank), Collections.emptyMap(), places, transports, name -> null);

		StepLocationPlan plan = plans.get("a");
		assertEquals(2, plan.size());
		assertEquals("Emir's Arena", plan.get(0).getLabel());
		assertEquals("Mage Training Arena", plan.get(1).getLabel());
	}

	@Test
	public void legacySingleHintViewStillUsesFinalEndpoint()
	{
		GuideBank bank = bank("B1", step("a",
			"Use a ring of dueling to Emir's Arena, then run to Mage Training Arena", "B1"));
		Map<String, StepLocationHint> hints = StepLocationPlanner.build(
			guide(bank), Collections.emptyMap(), places, transports, name -> null);

		assertEquals("Mage Training Arena", hints.get("a").getLabel());
	}

	private static Guide guide(GuideBank... banks)
	{
		Guide guide = new Guide();
		GuideEpisode episode = new GuideEpisode(1, "Episode 1");
		Collections.addAll(episode.getBanks(), banks);
		guide.getEpisodes().add(episode);
		return guide;
	}

	private static GuideBank bank(String id, GuideStep... steps)
	{
		GuideBank bank = new GuideBank(id, id, 1);
		Collections.addAll(bank.getSteps(), steps);
		return bank;
	}

	private static GuideStep step(String key, String text, String bankId)
	{
		return new GuideStep(key, text, 0, bankId);
	}
	@Test
	public void doesNotSplitNextToPhrase()
	{
		GuideBank bank = bank("B1", step("a", "Go next to Tithe Farm and begin the activity", "B1"));
		Map<String, StepLocationPlan> plans = StepLocationPlanner.buildPlans(guide(bank),
			Collections.emptyMap(), places, transports, ignored -> null);

		StepLocationPlan plan = plans.values().iterator().next();
		assertEquals(1, plan.size());
		assertEquals("Tithe Farm", plan.get(0).getLabel());
	}

}
