package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WikitextParserTest
{
	private static final String SAMPLE = "" +
		"== B0aty HCIM Guide v3.0 (70 Herblore Brickwall) ==\n" +
		"Some intro text that should be ignored.\n" +
		"=== Guide specific terminology ===\n" +
		"* This bullet is before any episode and must be ignored\n" +
		"== Episode 1 - Banks 1 through 24: ==\n" +
		"* A note before the first bank\n" +
		"=== Bank 1 ===\n" +
		"* Withdraw: Coins, Air Runes, Mind Runes (8 Inventory slots)\n" +
		"* Talk to [[Father Urhney]] in the lumbridge swamp for Ghost Speak Amulet. (2,1)\n" +
		"** Sub-step with {{plink|Leather Gloves}} and '''bold''' text\n" +
		"* Collect 3x Logs [Tree Gnome Village]\n" +
		"* Collect 3x Logs [Tree Gnome Village]\n" +
		"=== Bank 39A ===\n" +
		"* Talk to [[Jennifer_(Shayzien)|Jennifer_(Shayzien)]] for Client of Kourend (3,2)\n" +
		"== Episode 2 - Banks 24 through 75: ==\n" +
		"=== Bank 40 ===\n" +
		"* Kill a Chicken. Take Everything [Client of Kourend + Druidic Ritual]\n";

	@Test
	public void parsesEpisodesBanksAndSteps()
	{
		Guide guide = new WikitextParser().parse(SAMPLE);

		assertEquals(2, guide.getEpisodes().size());

		GuideEpisode ep1 = guide.getEpisodes().get(0);
		assertEquals(1, ep1.getNumber());
		// notes pseudo-bank + Bank 1 + Bank 39A
		assertEquals(3, ep1.getBanks().size());
		assertEquals("E1-notes", ep1.getBanks().get(0).getId());
		assertEquals("E1.B1", ep1.getBanks().get(1).getId());
		assertEquals("E1.B39A", ep1.getBanks().get(2).getId());

		GuideBank bank1 = ep1.getBanks().get(1);
		assertEquals(5, bank1.getSteps().size());
		assertEquals("Withdraw: Coins, Air Runes, Mind Runes (8 Inventory slots)", bank1.getSteps().get(0).getText());
	}

	@Test
	public void cleansWikiMarkup()
	{
		Guide guide = new WikitextParser().parse(SAMPLE);
		GuideBank bank1 = guide.getEpisodes().get(0).getBanks().get(1);

		assertEquals("Talk to Father Urhney in the lumbridge swamp for Ghost Speak Amulet. (2,1)",
			bank1.getSteps().get(1).getText());
		assertEquals("Sub-step with Leather Gloves and bold text", bank1.getSteps().get(2).getText());
		assertEquals(1, bank1.getSteps().get(2).getDepth());

		GuideBank bank39a = guide.getEpisodes().get(0).getBanks().get(2);
		assertEquals("Talk to Jennifer_(Shayzien) for Client of Kourend (3,2)",
			bank39a.getSteps().get(0).getText());
	}

	@Test
	public void duplicateStepsGetDistinctStableKeys()
	{
		Guide guide = new WikitextParser().parse(SAMPLE);
		GuideBank bank1 = guide.getEpisodes().get(0).getBanks().get(1);

		String k3 = bank1.getSteps().get(3).getKey();
		String k4 = bank1.getSteps().get(4).getKey();
		assertEquals(bank1.getSteps().get(3).getText(), bank1.getSteps().get(4).getText());
		assertNotEquals(k3, k4);

		// keys are stable across a re-parse
		Guide again = new WikitextParser().parse(SAMPLE);
		assertEquals(k3, again.getEpisodes().get(0).getBanks().get(1).getSteps().get(3).getKey());
	}

	@Test
	public void proseStartingWithBankIsNotASection()
	{
		// "Bank 9 duplicates Bank 8..." is a paragraph, not a section marker -
		// treating it as one would silently re-key later steps
		Guide g = new WikitextParser().parse("== Episode 1 - x ==\n"
			+ "'''Bank 8'''\n* step a\n"
			+ "Bank 9 duplicates Bank 8 if you skipped it\n* step b\n"
			+ "'''Bank 9'''\n* step c\n");
		assertEquals(2, g.getEpisodes().get(0).getBanks().size());
		assertEquals(2, g.getEpisodes().get(0).getBanks().get(0).getSteps().size());

		// but a bare anchored label (with optional "- location" suffix) is one
		Guide h = new WikitextParser().parse("== Episode 1 - x ==\nBank 12 - Falador\n* step\n");
		assertEquals("E1.B12", h.getEpisodes().get(0).getBanks().get(0).getId());
	}

	@Test
	public void recognizesBoldLineBankMarkers()
	{
		// the live guide marks banks as bold standalone lines, NOT headings
		String wiki = "== Episode 2 - Banks 24 through 75: ==\n"
			+ "'''Bank 40'''\n"
			+ "* Withdraw: Teleport Runes, Coins, Molten Glass (11 Inventory Slots)\n"
			+ "* Do a thing\n"
			+ "'''Bank 41'''\n"
			+ "* Another step\n";
		Guide g = new WikitextParser().parse(wiki);
		assertEquals(1, g.getEpisodes().size());
		assertEquals(2, g.getEpisodes().get(0).getBanks().size());
		assertEquals("E2.B40", g.getEpisodes().get(0).getBanks().get(0).getId());
		assertEquals(2, g.getEpisodes().get(0).getBanks().get(0).getSteps().size());
		assertEquals("E2.B41", g.getEpisodes().get(0).getBanks().get(1).getId());
	}

	@Test
	public void parsesChecklistTemplateFormatWithBankComments()
	{
		// the LIVE B0aty guide's real structure (verified against the actual
		// stored snapshot): banks are {{Checklist}} templates whose numbers are
		// computed by wiki parser functions; the only literal number is an HTML
		// comment before each block. Empty/computed titles without a comment
		// continue the current section; literal titles become named sections.
		String wiki = "===Episode 1 - Banks 1 through 2:===\n"
			+ "{{Youtube|abc123}}\n"
			+ "<!-- Bank 1 -->\n"
			+ "{{Checklist|title=Bank {{#expr:{{#var:bankNumber}}+1}}|\n"
			+ "* Withdraw: Coins, Spade (2 Inventory slots)\n"
			+ "* Talk to the [[Lumbridge Guide]]\n"
			+ "}}\n"
			+ "{{Var|bankNumber|{{#expr:{{#var:bankNumber}}+1}}}}\n"
			+ "{{Extimage|https://example.invalid/image.png|right|400|}}\n"
			+ "<!-- Bank 2A -->\n"
			+ "{{Checklist|title=Bank {{#expr:{{#var:bankNumber}}+1}}|\n"
			+ "* Second bank step\n"
			+ "}}\n"
			+ "{{Checklist|title=|* Inline continuation step\n"
			+ "* Another continuation step\n"
			+ "}}\n"
			+ "{{Checklist|title=Herblore|\n"
			+ "* Make potions\n"
			+ "}}\n";

		Guide g = new WikitextParser().parse(wiki);
		GuideEpisode ep = g.getEpisodes().get(0);
		assertEquals(3, ep.getBanks().size());
		assertEquals("E1.B1", ep.getBanks().get(0).getId());
		assertEquals("Bank 1", ep.getBanks().get(0).getTitle());
		assertEquals(2, ep.getBanks().get(0).getSteps().size());
		assertEquals("E1.B2A", ep.getBanks().get(1).getId());
		// the empty-title checklist continued Bank 2A (inline first item included)
		assertEquals(3, ep.getBanks().get(1).getSteps().size());
		assertEquals("Herblore", ep.getBanks().get(2).getTitle());
		assertEquals(2, g.numberedBanks());
	}

	@Test
	public void recognizesBankMarkersWrappedByWikiLayout()
	{
		String wiki = "== Episode 1 - x ==\n"
			+ "| style=\"text-align:center\" | '''Bank 1'''[[File:Bank.png|thumb|[[Varrock]] {{plink|Coins}}]]\n"
			+ "* step one\n"
			+ "* '''Bank 2'''\n"
			+ "* step two\n"
			+ "<div class=\"bank\">Bank 3 - Falador</div>\n"
			+ "* step three\n"
			+ "; Bank 4\n"
			+ "* step four\n";

		Guide g = new WikitextParser().parse(wiki);
		assertEquals(4, g.getEpisodes().get(0).getBanks().size());
		assertEquals("E1.B1", g.getEpisodes().get(0).getBanks().get(0).getId());
		assertEquals("E1.B2", g.getEpisodes().get(0).getBanks().get(1).getId());
		assertEquals("E1.B3", g.getEpisodes().get(0).getBanks().get(2).getId());
		assertEquals("E1.B4", g.getEpisodes().get(0).getBanks().get(3).getId());
		assertEquals("Bank 3 - Falador", g.getEpisodes().get(0).getBanks().get(2).getTitle());
	}

	@Test
	public void repeatedBankMarkerContinuesSameBankWithoutDuplicateKeys()
	{
		String wiki = "== Episode 1 - x ==\n"
			+ "'''Bank 1'''\n* same step\n"
			+ "'''Bank 1'''[[File:continued.png|thumb|continued screenshot]]\n* same step\n";
		Guide g = new WikitextParser().parse(wiki);
		GuideBank bank = g.getEpisodes().get(0).getBanks().get(0);
		assertEquals(1, g.getEpisodes().get(0).getBanks().size());
		assertEquals(2, bank.getSteps().size());
		assertNotEquals(bank.getSteps().get(0).getKey(), bank.getSteps().get(1).getKey());
	}

	@Test
	public void bankExtractorRejectsProseButAcceptsStandaloneVariants()
	{
		assertNull(WikitextParser.extractBankLabel("Bank 9 duplicates Bank 8 if skipped"));
		assertNull(WikitextParser.extractBankLabel("Go to '''Bank 9''' after the quest"));
		assertEquals("Bank 9", WikitextParser.extractBankLabel("* '''Bank 9'''"));
		assertEquals("Bank 39A", WikitextParser.extractBankLabel("| [[File:x.png|[[Nested link]]]] '''Bank 39A'''"));
		assertEquals("Bank 12", WikitextParser.extractBankLabel("File:bank12.png|'''Bank 12'''"));
		assertEquals("Bank 13", WikitextParser.extractBankLabel("[[File:bank13.png|thumb|'''Bank 13''']]"));
		assertEquals("Bank 14", WikitextParser.extractBankLabel("[[File:bank14.png|thumb|Bank 14]]"));
	}

	@Test
	public void neutralizesEntityEscapedMarkup()
	{
		// entity-escaped HTML must never survive into display strings: Swing
		// renders any label/tooltip starting with "<html>" as live HTML
		assertEquals("Bank 1",
			WikitextParser.cleanInline("&lt;html&gt;&lt;img src='http://evil/x.png'&gt;Bank 1"));
		// and &amp; must decode LAST so "&amp;lt;" can't double-decode into "<"
		assertEquals("a &lt; b", WikitextParser.cleanInline("a &amp;lt; b"));
	}

	@Test
	public void fallsBackToGenericChaptersForNonEpisodeGuides()
	{
		String generic = "== Getting started ==\n"
			+ "* Buy a hammer\n"
			+ "=== Lumbridge ===\n"
			+ "* Kill 5 goblins\n"
			+ "== Mid game ==\n"
			+ "* Do a thing\n";
		Guide g = new WikitextParser().parse(generic);
		assertEquals(2, g.getEpisodes().size());
		assertEquals("Getting started", g.getEpisodes().get(0).getTitle());
		assertEquals("E1-notes", g.getEpisodes().get(0).getBanks().get(0).getId());
		assertEquals("E1-lumbridge", g.getEpisodes().get(0).getBanks().get(1).getId());
		assertEquals(3, g.totalSteps());
	}

	@Test
	public void extractsNpcTargets()
	{
		assertEquals("Father Urhney",
			TargetExtractor.extract("Talk to Father Urhney in the lumbridge swamp for Ghost Speak Amulet. (2,1)"));
		assertEquals("Count Check",
			TargetExtractor.extract("Talk to Count Check and be teleported to Stronghold of Security (3,1)"));
		assertEquals("Aris",
			TargetExtractor.extract("Head South & Talk to Aris and start Demon Slayer (1,2,3,4)."));
		assertEquals("Jennifer",
			TargetExtractor.extract("Talk to Jennifer_(Shayzien) for Client of Kourend (3,2)"));
		assertNull(TargetExtractor.extract("Mine 10x Clay at varrock west mine"));

		assertTrue(TargetExtractor.namesMatch("Jennifer", "Jennifer"));
		assertTrue(TargetExtractor.namesMatch("Father Urhney", "Father Urhney"));
	}
}
