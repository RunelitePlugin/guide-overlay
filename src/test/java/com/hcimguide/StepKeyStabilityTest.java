package com.hcimguide;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Saved progress is keyed by step key, and a key hashes the step text. Every
 * change to how text is displayed therefore has to leave a trail from the key
 * the previous parser generation minted to the current one. The "previous"
 * texts below are written out literally, exactly as that generation produced
 * them, so these tests keep failing if the rebuilt keys ever drift.
 */
public class StepKeyStabilityTest
{
	private static String key(String sectionId, String text, int occurrence)
	{
		return sectionId + "#" + Integer.toHexString(text.hashCode()) + "#" + occurrence;
	}

	private static Set<String> replay(Guide guide, String... saved)
	{
		Set<String> progress = new HashSet<>(Arrays.asList(saved));
		ProgressKeyMigration.migrateLegacyKeys(guide, progress);
		ProgressKeyMigration.migrateSplitKeys(guide, progress);
		return progress;
	}

	private static Set<String> keysOf(Guide guide)
	{
		Set<String> keys = new HashSet<>();
		for (GuideEpisode episode : guide.getEpisodes())
		{
			for (GuideBank bank : episode.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					assertTrue("duplicate key " + step.getKey(), keys.add(step.getKey()));
				}
			}
		}
		return keys;
	}

	// ---- wikitext -----------------------------------------------------------

	private static final String WIKI = ""
		+ "===Episode 1 - Banks 1 through 2===\n"
		+ "<!-- Bank 1 -->\n"
		+ "{{Checklist|title=Bank {{#expr:{{#var:bankNumber}}+1}}|\n"
		+ "* Withdraw: Coins, Spade\n"
		+ "* Ardy Cloak -> DKP\n"
		+ "* Make a potion (Vial > Tarromin > Limpwurt)\n"
		+ "* Ardy Cloak -> DKP\n"
		+ "* Ardy Cloak - DKP\n"
		+ "* Talk to [[Yanni Salika]] (2,5,1) [One Small Favour]}}\n"
		+ "<!-- Bank 2 -->\n"
		+ "{{Checklist|title=Bank {{#expr:{{#var:bankNumber}}+1}}|\n"
		+ "IF 85 Crafting\n"
		+ "* Sell the runes\n"
		+ "* IF 85 Crafting\n"
		+ "}}\n";

	@Test
	public void unchangedStepsKeepTheirKeys()
	{
		Guide guide = new WikitextParser().parse(WIKI);
		Set<String> keys = keysOf(guide);
		assertTrue(keys.contains(key("E1.B1", "Withdraw: Coins, Spade", 0)));
		assertTrue(keys.contains(key("E1.B2", "Sell the runes", 0)));
		assertFalse(guide.getLegacySplitKeys().containsKey(key("E1.B1", "Withdraw: Coins, Spade", 0)));
	}

	@Test
	public void arrowStepsMapFromTheirFlattenedKeys()
	{
		Guide guide = new WikitextParser().parse(WIKI);
		Set<String> keys = keysOf(guide);
		List<GuideStep> steps = guide.getEpisodes().get(0).getBanks().get(0).getSteps();
		assertEquals("Ardy Cloak → DKP", steps.get(1).getText());
		assertEquals("Make a potion (Vial → Tarromin → Limpwurt)", steps.get(2).getText());
		assertEquals("Ardy Cloak - DKP", steps.get(4).getText());

		// the previous generation dropped every ">": three steps shared one
		// text, and the occurrence suffix told them apart in document order
		String firstArrow = key("E1.B1", "Ardy Cloak - DKP", 0);
		String secondArrow = key("E1.B1", "Ardy Cloak - DKP", 1);
		String literalDash = key("E1.B1", "Ardy Cloak - DKP", 2);
		String potion = key("E1.B1", "Make a potion (Vial Tarromin Limpwurt)", 0);

		assertEquals(Collections.singletonList(steps.get(1).getKey()), guide.getLegacySplitKeys().get(firstArrow));
		assertEquals(Collections.singletonList(steps.get(3).getKey()), guide.getLegacySplitKeys().get(secondArrow));
		assertEquals(Collections.singletonList(steps.get(4).getKey()), guide.getLegacySplitKeys().get(literalDash));
		assertEquals(Collections.singletonList(steps.get(2).getKey()), guide.getLegacySplitKeys().get(potion));

		Set<String> migrated = replay(guide, secondArrow, potion, literalDash);
		assertEquals(new HashSet<>(Arrays.asList(
			steps.get(3).getKey(), steps.get(2).getKey(), steps.get(4).getKey())), migrated);
		assertTrue(keys.containsAll(migrated));
	}

	@Test
	public void blockClosingBracesLeaveTheTextAndTheOldKeyFollows()
	{
		Guide guide = new WikitextParser().parse(WIKI);
		GuideStep step = guide.getEpisodes().get(0).getBanks().get(0).getSteps().get(5);
		assertEquals("Talk to Yanni Salika (2,5,1) [One Small Favour]", step.getText());
		String old = key("E1.B1", "Talk to Yanni Salika (2,5,1) [One Small Favour]}}", 0);
		assertEquals(Collections.singleton(step.getKey()), replay(guide, old));

		// braces that close a template of the bullet's own are not block closers
		Guide inline = new WikitextParser().parse("==Episode 1==\n* Take {{plink|Rope}}\n* next\n");
		assertEquals("Take Rope", inline.getEpisodes().get(0).getBanks().get(0).getSteps().get(0).getText());
		assertTrue(inline.getLegacySplitKeys().isEmpty());
	}

	@Test
	public void skippedMarksFollowTheSameMap()
	{
		// skipped steps are replayed through the split map only
		Guide guide = new WikitextParser().parse(WIKI);
		List<GuideStep> steps = guide.getEpisodes().get(0).getBanks().get(0).getSteps();
		Set<String> skipped = new HashSet<>(Arrays.asList(
			key("E1.B1", "Ardy Cloak - DKP", 1),
			key("E1.B1", "Talk to Yanni Salika (2,5,1) [One Small Favour]}}", 0),
			key("E1.B1", "Withdraw: Coins, Spade", 0)));
		assertTrue(ProgressKeyMigration.migrateSplitKeys(guide, skipped));
		assertEquals(new HashSet<>(Arrays.asList(
			steps.get(3).getKey(), steps.get(5).getKey(), steps.get(0).getKey())), skipped);
		assertTrue(guide.getLegacyStepKeys().isEmpty());

		// KNOWN BOUNDED EDGE: the first arrow step's earlier key is, in this
		// fixture, exactly the key the literal "Ardy Cloak - DKP" bullet has
		// now. A live key is never treated as a legacy one, so that tick
		// stays where it is - on the same words, one row further down.
		Set<String> ambiguous = new HashSet<>(Collections.singleton(key("E1.B1", "Ardy Cloak - DKP", 0)));
		assertFalse(ProgressKeyMigration.migrateSplitKeys(guide, ambiguous));
		assertEquals(steps.get(4).getKey(), ambiguous.iterator().next());
	}

	@Test
	public void noteRowsNeverTakeOverAStepKey()
	{
		Guide guide = new WikitextParser().parse(WIKI);
		keysOf(guide);
		List<GuideStep> steps = guide.getEpisodes().get(0).getBanks().get(1).getSteps();
		assertEquals(3, steps.size());
		assertEquals("IF 85 Crafting", steps.get(0).getText());
		assertEquals("IF 85 Crafting", steps.get(2).getText());
		// the real bullet keeps the key it always had; the note row above it
		// lives in its own key space
		assertEquals(key("E1.B2", "IF 85 Crafting", 0), steps.get(2).getKey());
		assertNotEquals(steps.get(0).getKey(), steps.get(2).getKey());
		assertEquals(Collections.singleton(steps.get(2).getKey()),
			replay(guide, key("E1.B2", "IF 85 Crafting", 0)));
	}

	@Test
	public void plainLinesOutsideAChecklistStayIgnored()
	{
		Guide guide = new WikitextParser().parse("==Episode 1==\nIntro prose.\n{{Checklist|title=A|\n* one\n}}\nEnd of Episode 1\n");
		assertEquals(1, guide.totalSteps());
	}

	@Test
	public void changedTextAlsoMapsFromTheSingleNotesSectionEra()
	{
		Guide guide = new WikitextParser().parse(WIKI);
		GuideStep arrow = guide.getEpisodes().get(0).getBanks().get(0).getSteps().get(3);
		String notesEra = key("E1-notes", "Ardy Cloak - DKP", 1);
		assertEquals(Collections.singleton(arrow.getKey()), replay(guide, notesEra));
	}

	@Test
	public void boundedSectionIdMapsFromTheUnboundedOne()
	{
		// 120-character heading: its slug no longer fits the id, and progress
		// saved under the full-length id has to follow
		StringBuilder title = new StringBuilder();
		for (int i = 0; i < 20; i++)
		{
			title.append("part").append(i < 10 ? "0" : "").append(i).append(' ');
		}
		String heading = title.toString().trim();
		Guide guide = new WikitextParser().parse("==Episode 1==\n===" + heading + "===\n* only step\n");
		GuideBank bank = guide.getEpisodes().get(0).getBanks().get(0);
		assertTrue(bank.getId().length() <= 72);
		assertEquals(heading, bank.getTitle());
		String old = key("E1-" + heading.replace(' ', '-'), "only step", 0);
		assertEquals(Collections.singleton(bank.getSteps().get(0).getKey()), replay(guide, old));
	}

	// ---- JSON ---------------------------------------------------------------

	private static String run(String text)
	{
		return "{\"text\":\"" + text + "\",\"formatting\":{}}";
	}

	private static String jsonStep(String items, String... runs)
	{
		return "{\"content\":[" + String.join(",", runs) + "],\"nestedContent\":[],"
			+ "\"metadata\":{\"items_needed\":\"" + items + "\"}}";
	}

	private static final String JSON = "{\"chapters\":[{\"title\":\"Chapter 1\",\"sections\":[{\"title\":\"1.1\",\"steps\":["
		+ jsonStep("none", run("Chop logs near the castle."))
		+ "," + jsonStep("none", run("The order is Focus > "), run("Instances > Ring."))
		+ "," + jsonStep("none", run("Start Wanted! and continue it until Taverley dungeon. Then bank your items."))
		+ "," + jsonStep("none", run("Chop logs near the castle."))
		+ "]}]}]}";

	@Test
	public void jsonUnchangedParagraphsKeepTheirKeys()
	{
		Guide guide = new JsonGuideParser().tryParse(JSON);
		Set<String> keys = keysOf(guide);
		assertTrue(keys.contains(key("C1.S1", "Chop logs near the castle.", 0)));
		assertTrue(keys.contains(key("C1.S1", "Chop logs near the castle.", 1)));
		assertTrue(keys.contains(key("C1.S1", "Then bank your items.", 0)));
	}

	@Test
	public void jsonArrowParagraphMapsFromEveryEarlierGeneration()
	{
		Guide guide = new JsonGuideParser().tryParse(JSON);
		GuideStep step = guide.getEpisodes().get(0).getBanks().get(0).getSteps().get(1);
		assertEquals("The order is Focus → Instances → Ring.", step.getText());

		// previous generation: ">" dropped, runs joined verbatim
		String previous = key("C1.S1", "The order is Focus Instances Ring.", 0);
		assertEquals(Collections.singleton(step.getKey()), replay(guide, previous));
		// oldest generation: runs joined with a space, which this text survives
		// unchanged - so its key is the same and takes the same route
		assertFalse(guide.getLegacyStepKeys().containsKey(previous));
	}

	@Test
	public void jsonSentenceJoinedAtAQuestNameMapsBothHalves()
	{
		Guide guide = new JsonGuideParser().tryParse(JSON);
		List<GuideStep> steps = guide.getEpisodes().get(0).getBanks().get(0).getSteps();
		assertEquals("Start Wanted! and continue it until Taverley dungeon.", steps.get(2).getText());
		assertEquals("Then bank your items.", steps.get(3).getText());

		// the previous generation ended a sentence at every "!"
		String firstHalf = key("C1.S1", "Start Wanted!", 0);
		String secondHalf = key("C1.S1", "and continue it until Taverley dungeon.", 0);
		assertEquals(Collections.singleton(steps.get(2).getKey()), replay(guide, firstHalf));
		assertEquals(Collections.singleton(steps.get(2).getKey()), replay(guide, secondHalf));

		// and the generation before that kept the paragraph whole
		String paragraph = key("C1.S1",
			"Start Wanted! and continue it until Taverley dungeon. Then bank your items.", 0);
		assertEquals(new HashSet<>(Arrays.asList(steps.get(2).getKey(), steps.get(3).getKey())),
			replay(guide, paragraph));
	}

	@Test
	public void sentenceStillEndsAtAnExclamationBeforeACapital()
	{
		assertEquals(Arrays.asList("Do not die here!", "Bank everything first."),
			JsonGuideParser.splitDigestible("Do not die here! Bank everything first."));
		assertEquals(Collections.singletonList("Finish Contact! (pick up the Keris on the way)."),
			JsonGuideParser.splitDigestible("Finish Contact! (pick up the Keris on the way)."));
		assertEquals(2, JsonGuideParser.splitDigestible("Start Wanted! and continue it until Taverley.", true).size());
	}

	@Test
	public void jsonItemListIsCarriedWholeWhileTheKeyTextStaysCut()
	{
		String items = "knife, tinderbox, 3 oak logs, 10 steel nails, hammer, chisel, bucket, leather gloves, "
			+ "50 feathers, Shantay pass, 3 waterskins, bronze pickaxe, pestle and mortar, 290gp";
		Guide guide = new JsonGuideParser().tryParse("{\"chapters\":[{\"sections\":[{\"steps\":["
			+ jsonStep(items, run("Go to the Varrock East bank.")) + "]}]}]}");
		GuideStep step = guide.getEpisodes().get(0).getBanks().get(0).getSteps().get(0);

		// the text - and with it the key - is exactly what it always was
		String cut = items.substring(0, 119) + "…";
		assertEquals("Go to the Varrock East bank. (Items: " + cut + ")", step.getText());
		assertEquals(key("C1.S1", step.getText(), 0), step.getKey());
		assertEquals(items, step.getItemsNeeded());

		List<ItemReq> shown = StepItemRequirements.displayFor(step);
		assertEquals(14, shown.size());
		// one item, not "pestle" and "mortar"
		assertEquals("pestle and mortar", shown.get(12).getName());
		assertEquals("Coins", shown.get(13).getName());
		assertEquals(290, shown.get(13).getQuantity());

		// a caller that only has the text gets the complete entries of the
		// cut list - never the half word the cut left behind
		List<ItemReq> fromText = ItemListParser.parseItemsSuffix(step.getText());
		for (ItemReq req : fromText)
		{
			assertFalse(req.getName(), req.getName().contains("…"));
		}
		assertEquals("Shantay pass", fromText.get(fromText.size() - 1).getName());
		assertNull(ItemListParser.parseItemsSuffix("x (Items: wate…)"));
	}
}
