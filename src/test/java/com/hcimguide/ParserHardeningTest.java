package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Hostile-input bounds for the guide parsers. Guide text is remote and, for
 * wiki guides, publicly editable. Each case is super-linear - seconds to
 * hours of CPU, or the whole heap, for a few kilobytes of input - unless the
 * bound it exercises holds. The timeouts are generous (bounded code needs
 * milliseconds), so losing a bound fails the test rather than slowing it.
 */
public class ParserHardeningTest
{
	private static final String EPISODE = "===Episode 1===\n* first step\n";

	private static String repeat(String s, int n)
	{
		StringBuilder sb = new StringBuilder(s.length() * n);
		for (int i = 0; i < n; i++)
		{
			sb.append(s);
		}
		return sb.toString();
	}

	private static Guide parse(String wikitext)
	{
		return new WikitextParser().parse(wikitext);
	}

	// ---- raw-line patterns --------------------------------------------------

	@Test(timeout = 5000)
	public void headingPaddedWithSpacesIsLinear()
	{
		// "==" + spaces + text and no closing "==": cubic for a regex
		Guide guide = parse(EPISODE + "==" + repeat(" ", 200_000) + "x\n* second step\n");
		assertEquals(2, guide.totalSteps());
	}

	@Test
	public void headingLevelFollowsWikiSemantics()
	{
		assertEquals(2, WikitextParser.headingLevel("==A=="));
		assertEquals(3, WikitextParser.headingLevel("=== A ==="));
		assertEquals(6, WikitextParser.headingLevel("======A======"));
		// the shorter run decides the level; surplus "=" belong to the title
		assertEquals(2, WikitextParser.headingLevel("===A=="));
		assertEquals(2, WikitextParser.headingLevel("==A==="));
		assertEquals(6, WikitextParser.headingLevel("=======A======="));
		assertEquals(2, WikitextParser.headingLevel("===="));
		assertEquals(2, WikitextParser.headingLevel("====="));
		assertEquals(0, WikitextParser.headingLevel("=A="));
		assertEquals(0, WikitextParser.headingLevel("==A"));
		assertEquals(0, WikitextParser.headingLevel("A=="));
		assertEquals(0, WikitextParser.headingLevel("==="));

		Guide guide = parse("== Episode 1 - start ==\n=== Bank 1 ===\n* a\n===Odd title==\n* b\n");
		assertEquals("Episode 1 - start", guide.getEpisodes().get(0).getTitle());
		assertEquals("E1.B1", guide.getEpisodes().get(0).getBanks().get(0).getId());
		assertEquals("=Odd title", guide.getEpisodes().get(0).getBanks().get(1).getTitle());
	}

	@Test(timeout = 5000)
	public void bankCommentPaddedWithSpacesIsLinear()
	{
		Guide guide = parse(EPISODE + repeat("<!--Bank" + repeat(" ", 4000) + "x\n", 500)
			+ "<!--  Bank   #  7  -->\n{{Checklist|title=x|\n* in bank seven\n}}\n");
		assertEquals("E1.B7", guide.getEpisodes().get(0).getBanks().get(1).getId());
	}

	@Test(timeout = 5000)
	public void unclosedBoldRunPaddedWithSpacesIsLinear()
	{
		// five hundred lines, each within the per-line length cap
		Guide guide = parse(EPISODE + repeat("'''" + repeat(" ", 4000) + "x\n", 500)
			+ "'''  Bank 12  '''\n* in bank twelve\n");
		assertEquals("Bank 12", guide.getEpisodes().get(0).getBanks().get(1).getTitle());
	}

	@Test(timeout = 5000)
	public void tableCellJunkIsLinear()
	{
		String cells = repeat("|" + repeat(" =", 2000) + "\n", 300)
			+ repeat(repeat("|", 4000) + "x\n", 300)
			+ repeat("File:" + repeat("a", 4000) + "\n", 300);
		Guide guide = parse(EPISODE + cells + "| style=\"width:50%\" | Bank 3 |\n* in bank three\n");
		assertEquals("E1.B3", guide.getEpisodes().get(0).getBanks().get(1).getId());
	}

	@Test(timeout = 5000)
	public void unclosedExternalLinksAreLinear()
	{
		String hostile = repeat("* " + repeat("[http://", 510) + "\n", 300)
			+ repeat("* " + repeat("[http://a ", 400) + "\n", 300);
		assertEquals(601, parse(EPISODE + hostile).totalSteps());
		// and the scanner still does what the patterns did
		assertEquals("see the map and go", WikitextParser.cleanInline(
			"see [https://example.org/a_b the map] and [http://example.org/x] go"));
		assertEquals("[http://example.org no close", WikitextParser.cleanInline("[http://example.org no close"));
		assertEquals("b", WikitextParser.cleanInline("[http://a]x b]"));
	}

	@Test(timeout = 5000)
	public void megabyteLinesNeverReachAPattern()
	{
		String megabyte = repeat("a ", 512 * 1024);
		Guide guide = parse(EPISODE
			+ megabyte + "\n"                              // prose
			+ "{{Checklist|title=Big|* " + megabyte + "\n" // opener with an oversized inline item
			+ "<!-- Bank" + megabyte + "\n"
			+ "== " + megabyte + "\n"                       // unclosed heading
			+ "* kept\n");
		assertEquals(2, guide.totalSteps());
		assertTrue("a dropped list item makes the import incomplete", guide.isTruncated());

		// an oversized line that is not a list item is skipped silently
		assertFalse(parse(EPISODE + megabyte + "\n").isTruncated());
	}

	// ---- unbounded titles, ids and keys ------------------------------------

	@Test(timeout = 20000)
	public void checklistTitleCannotMultiplyIntoEveryStepKey()
	{
		// a title must not be copied, whole, into each of the block's step keys
		StringBuilder page = new StringBuilder(EPISODE);
		page.append("{{Checklist|title=").append(repeat("Long title ", 4000 / 11)).append("|\n");
		for (int i = 0; i < 20_000; i++)
		{
			page.append("* step ").append(i).append('\n');
		}
		page.append("}}\n");
		// an opener longer than any line cap still opens its section - with the title cut short
		page.append("{{Checklist|title=").append(repeat("x", 240_000)).append("|\n* after\n}}\n");

		Guide guide = parse(page.toString());
		assertEquals(20_002, guide.totalSteps());
		for (GuideEpisode episode : guide.getEpisodes())
		{
			assertTrue(episode.getTitle().length() <= 200);
			for (GuideBank bank : episode.getBanks())
			{
				assertTrue(bank.getTitle().length() <= 200);
				assertTrue(bank.getId().length() <= 72);
				for (GuideStep step : bank.getSteps())
				{
					// the persistence layer rejects keys over 512 characters
					assertTrue(step.getKey().length() <= 512);
				}
			}
		}
	}

	@Test
	public void longIdsAreBoundedButStayDistinct()
	{
		String shared = repeat("word ", 30);
		Guide guide = parse("==Episode 1==\n===" + shared + "one===\n* a\n===" + shared + "two===\n* b\n"
			+ "===Bank " + repeat("9", 150) + "===\n* c\n");
		GuideEpisode episode = guide.getEpisodes().get(0);
		assertEquals(3, episode.getBanks().size());
		assertFalse(episode.getBanks().get(0).getId().equals(episode.getBanks().get(1).getId()));
		for (GuideBank bank : episode.getBanks())
		{
			assertTrue(bank.getId().length() <= 72);
		}
	}

	@Test
	public void shortIdsAreNeverRewritten()
	{
		// a 64-character title slug is the longest that must survive verbatim
		String title = repeat("abcdefg ", 8).trim();
		Guide guide = parse("==Episode 123==\n===" + title + "===\n* a\n===Bank 39A===\n* b\n");
		assertEquals("E123-" + title.replace(' ', '-'), guide.getEpisodes().get(0).getBanks().get(0).getId());
		assertEquals("E123.B39A", guide.getEpisodes().get(0).getBanks().get(1).getId());
		assertTrue(guide.getLegacySplitKeys().isEmpty());
	}

	@Test
	public void jsonTitlesAreCapped()
	{
		String longTitle = repeat("t", 5000);
		Guide guide = new JsonGuideParser().tryParse("{\"chapters\":[{\"title\":\"" + longTitle
			+ "\",\"sections\":[{\"title\":\"" + longTitle
			+ "\",\"steps\":[{\"content\":[{\"text\":\"Talk to the guide here.\"}]}]}]}]}");
		assertEquals(200, guide.getEpisodes().get(0).getTitle().length());
		assertEquals(200, guide.getEpisodes().get(0).getBanks().get(0).getTitle().length());
	}

	// ---- per-step extractors -----------------------------------------------

	@Test(timeout = 10000)
	public void perStepExtractorsAreBoundedOnOversizedSteps()
	{
		String[] hostile = {
			"(Items: " + repeat("(", 4000),
			"(Items: " + repeat("a [", 1300),
			repeat("[", 4000),
			repeat("[[", 2000),
			repeat("[[a|", 1000),
			repeat("a, ", 1300),
			repeat("a and b & ", 400),
			"Talk to " + repeat("Aa ", 1300),
			repeat("(1,", 1300),
			repeat("take the ", 440),
			repeat("start a ", 500),
		};
		for (int round = 0; round < 300; round++)
		{
			for (String text : hostile)
			{
				assertNull(ConditionParser.parse(text));
				assertEquals(StepPhaseParser.Mode.MANUAL, StepPhaseParser.parse(text).getMode());
				assertNull(StepItemRequirements.displayFor(new GuideStep("k", text, 0, "b")));
				ItemListParser.parseGather(text);
				DialogSequenceParser.extract(text);
				DialogSequenceParser.npcBefore(text);
				TargetExtractor.extract(text);
				TargetExtractor.extractSecondary(text);
				TargetExtractor.objectWordsIn(text);
				StepTextSemantic.classify(text);
				TransportResolver.hasTransportIntent(text);
			}
		}
	}

	@Test(timeout = 10000)
	public void clauseSplittingIsLinearInSeparators()
	{
		// just under the analysis cap, nothing but separators and actions
		String text = repeat("go, ", 249);
		for (int i = 0; i < 100; i++)
		{
			StepPhaseParser.parse(text);
		}
		// separators still split where an action follows them
		assertEquals(2, StepPhaseParser.parse("Go to Varrock, talk to Aubury").getClauses().size());
		assertEquals(1, StepPhaseParser.parse("Go to Varrock, the big city").getClauses().size());
		assertEquals(2, StepPhaseParser.parse("Go to Catherby and boat to Brimhaven").getClauses().size());
	}

	@Test
	public void itemsSuffixHonoursTheItemParseCap()
	{
		assertNull(ItemListParser.parseItemsSuffix("x (Items: " + repeat("a, ", 400) + ")"));
		assertNull(ItemListParser.parseItemsNeeded(repeat("a, ", 400)));
		assertEquals(2, ItemListParser.parseItemsSuffix("x (Items: knife, logs)").size());
	}
}
