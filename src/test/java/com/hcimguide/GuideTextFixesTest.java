package com.hcimguide;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** How guide text is turned into display text: arrows, entities, invisible characters, line endings. */
public class GuideTextFixesTest
{
	private static List<GuideStep> steps(String wikitext)
	{
		Guide guide = new WikitextParser().parse(wikitext);
		return guide.getEpisodes().get(0).getBanks().get(0).getSteps();
	}

	// ---- arrows -------------------------------------------------------------

	@Test
	public void arrowsSurviveAsArrows()
	{
		assertEquals("Ardy Cloak → DKP", WikitextParser.cleanInline("Ardy Cloak -> DKP"));
		assertEquals("Sea Slug→Fishing Contest", WikitextParser.cleanInline("Sea Slug->Fishing Contest"));
		assertEquals("(Vial of water → Toadflax → Toad's Legs)",
			WikitextParser.cleanInline("(Vial of water > Toadflax > Toad's Legs)"));
		assertEquals("Focus → Instances → Ring", WikitextParser.sanitizeDisplay("Focus > Instances > Ring"));
	}

	@Test
	public void noAngleBracketEverSurvives()
	{
		String[] inputs = {
			"Ardy Cloak -> DKP", "a > b", "a >b", "a> b", ">", "->", "a <- b", "x < y > z",
			"<html><b>bold</b>", "&lt;html&gt;x", "&#60;html&#62;x", "&#x3C;html&#x3E;x",
			"<b >tag", "a <!-- note --> b", "a <!-- x -> y --> b", "1 < 2 -> 3 > 4",
		};
		for (String input : inputs)
		{
			String wiki = WikitextParser.cleanInline(input);
			String plain = WikitextParser.sanitizeDisplay(input);
			assertFalse(input + " -> " + wiki, wiki.contains("<") || wiki.contains(">"));
			assertFalse(input + " -> " + plain, plain.contains("<") || plain.contains(">"));
		}
		assertEquals("a b", WikitextParser.cleanInline("a <!-- note --> b"));
		assertEquals("boldx", WikitextParser.cleanInline("<html><b>bold</b>&lt;html&gt;x"));
	}

	@Test
	public void anArrowNoLongerPairsWithAnEarlierLessThanSign()
	{
		// the text between "<" and the arrow's ">" is not a tag
		assertEquals("If HP 20 eat, then Ardy Cloak → DKP",
			WikitextParser.cleanInline("If HP < 20 eat, then Ardy Cloak -> DKP"));
	}

	@Test
	public void arrowStepsAreTransportAgain()
	{
		for (String source : new String[]{
			"Games necklace -> Wintertodt", "Digsite Pendant -> Digsite", "Ring of dueling -> Castle Wars",
			"Necklace of passage > The Outpost", "Kharedst's Memoirs -> Piscarillius (2)"})
		{
			String text = WikitextParser.cleanInline(source);
			assertEquals(text, StepTextSemantic.Kind.TRANSPORT, StepTextSemantic.classify(text));
			assertTrue(text, TransportResolver.hasTransportIntent(text));
		}
	}

	@Test
	public void arrivalAloneNeverCompletesAnArrowStepThatHasMoreToDo()
	{
		// flattened to "-", both of these read as one travel clause
		String grain = WikitextParser.cleanInline("Falador Teleport -> 10 Grain south of Doric -> Tegid (2)");
		assertNotEquals(StepPhaseParser.Mode.PURE_TRAVEL, StepPhaseParser.parse(grain).getMode());
		String spellbook = WikitextParser.cleanInline("Teleport to POH -> Lunar Spellbook");
		assertEquals(StepPhaseParser.Mode.TRAVEL_THEN_TASK, StepPhaseParser.parse(spellbook).getMode());

		String minecart = WikitextParser.cleanInline("Minecart -> Shayzien East (B)");
		assertEquals(StepPhaseParser.Mode.PURE_TRAVEL, StepPhaseParser.parse(minecart).getMode());
	}

	// ---- entities and invisible characters ----------------------------------

	@Test
	public void commonEntitiesAreDecoded()
	{
		assertEquals("5 × 3 – ok… a → b → c → d",
			WikitextParser.cleanInline("5 &times; 3 &ndash; ok&hellip; a &rarr; b &#8594; c &#x2192; d"));
		assertEquals("Tom's", WikitextParser.cleanInline("Tom&apos;s"));
		// an escaped reference is literal text, decoded once and no further
		assertEquals("&rarr; &#60;", WikitextParser.cleanInline("&amp;rarr; &amp;#60;"));
		// unknown and out-of-range references stay as written
		assertEquals("&bogus; &#99999999; &#xD800;", WikitextParser.cleanInline("&bogus; &#99999999; &#xD800;"));
	}

	@Test
	public void invisibleAndDirectionOverrideCharactersAreDropped()
	{
		String hostile = "Talk\u0000 to\u0007 \u202EBob\u202C the\u200B Bui\u00ADlder\u2066!\u2069\uFEFF";
		assertEquals("Talk to Bob the Builder!", WikitextParser.cleanInline(hostile));
		assertEquals("Talk to Bob the Builder!", WikitextParser.sanitizeDisplay(hostile));
		// separators and no-break spaces are spaces, not nothing
		assertEquals("a b c d", WikitextParser.cleanInline("a\u00A0b\u2028c\u2029d"));
		assertEquals("tab and newline", WikitextParser.sanitizeDisplay("tab\tand\nnewline"));
	}

	// ---- line structure -----------------------------------------------------

	@Test
	public void byteOrderMarkDoesNotHideTheFirstHeading()
	{
		List<GuideStep> steps = steps("\uFEFF===Episode 1===\n* first\n* second\n");
		assertEquals(2, steps.size());
		assertEquals("first", steps.get(0).getText());

		Guide json = new JsonGuideParser().tryParse(
			"\uFEFF{\"chapters\":[{\"sections\":[{\"steps\":[{\"content\":[{\"text\":\"Read the signpost here.\"}]}]}]}]}");
		assertEquals(1, json.totalSteps());
		assertTrue(JsonGuideParser.looksLikeJsonGuide("\uFEFF{\"chapters\":[]}"));
	}

	@Test
	public void everyLineEndingStyleSplitsLines()
	{
		assertEquals(3, steps("===Episode 1===\r* a\r* b\r* c\r").size());
		assertEquals(3, steps("===Episode 1===\r\n* a\r\n* b\r\n* c\r\n").size());
		assertEquals(3, steps("===Episode 1===\n* a\r* b\r\n* c").size());
	}

	@Test
	public void bulletDepthIsClamped()
	{
		StringBuilder stars = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			stars.append('*');
		}
		List<GuideStep> steps = steps("===Episode 1===\n* top\n** child\n" + stars + " deep\n");
		assertEquals(0, steps.get(0).getDepth());
		assertEquals(1, steps.get(1).getDepth());
		assertEquals(6, steps.get(2).getDepth());
	}

	@Test
	public void plainLinesInsideAChecklistBecomeNoteRows()
	{
		List<GuideStep> steps = steps("===Episode 1===\n<!-- Bank 1 -->\n{{Checklist|title=x|\n"
			+ "Once you have your [[Bow of Faerdhinen]]\n"
			+ "* Head to Fossil Island\n"
			+ "{{Extimage|https://example.org/a.png|right|400|}}\n"
			+ "| table junk\n"
			+ "Video Guide: https://youtu.be/O6LDG19Gk5M\n"
			+ "* Bank\n"
			+ "}}\n"
			+ "End of Episode 1\n");
		assertEquals(3, steps.size());
		assertEquals("Once you have your Bow of Faerdhinen", steps.get(0).getText());
		assertEquals(0, steps.get(0).getDepth());
		assertEquals("Head to Fossil Island", steps.get(1).getText());
		assertEquals("Bank", steps.get(2).getText());
	}
}
