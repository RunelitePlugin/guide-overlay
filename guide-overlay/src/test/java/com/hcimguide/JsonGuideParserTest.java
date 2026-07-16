package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JsonGuideParserTest
{
	private static final String SAMPLE = "{"
		+ "\"title\": \"BRUHsailer Complete Guide\","
		+ "\"chapters\": ["
		+ "  {\"title\": \"Chapter 1: Get to da chopper\", \"sections\": ["
		+ "    {\"title\": \"1.1: Tutorial island\", \"steps\": ["
		+ "      {\"content\": [{\"text\": \"Choose a swag look\"}], \"metadata\": {\"items_needed\": \"none\"}},"
		+ "      {\"content\": [{\"text\": \"Complete tutorial\"}], \"nestedContent\": [{\"level\": 1, \"content\": [{\"text\": \"talk to the guide\"}]}], \"metadata\": {\"items_needed\": \"Bronze axe, Tinderbox\"}}"
		+ "    ]}"
		+ "  ]}"
		+ "]}";

	@Test
	public void detectsJsonGuideVsWikitext()
	{
		assertTrue(JsonGuideParser.looksLikeJsonGuide(SAMPLE));
		assertFalse(JsonGuideParser.looksLikeJsonGuide("== Episode 1 ==\n* step"));
		assertFalse(JsonGuideParser.looksLikeJsonGuide("{{template}} wiki text"));
		assertFalse(JsonGuideParser.looksLikeJsonGuide(null));
	}

	@Test
	public void mapsChaptersSectionsSteps()
	{
		Guide g = new JsonGuideParser().parse(SAMPLE);
		assertEquals(1, g.getEpisodes().size());
		GuideEpisode ch = g.getEpisodes().get(0);
		assertEquals("Chapter 1: Get to da chopper", ch.getTitle());
		assertEquals(1, ch.getBanks().size());
		GuideBank sec = ch.getBanks().get(0);
		assertEquals("C1.S1", sec.getId());
		// two steps + one nested
		assertEquals(3, sec.getSteps().size());
		assertEquals("Choose a swag look", sec.getSteps().get(0).getText());
		assertTrue(sec.getSteps().get(1).getText().contains("(Items: Bronze axe, Tinderbox)"));
		assertEquals(1, sec.getSteps().get(2).getDepth());
	}

	@Test
	public void stripsHtmlFromStepText()
	{
		Guide g = new JsonGuideParser().parse(
			"{\"chapters\":[{\"sections\":[{\"steps\":[{\"content\":[{\"text\":\"<html><img src=x>hi\"}]}]}]}]}");
		String t = g.getEpisodes().get(0).getBanks().get(0).getSteps().get(0).getText();
		assertFalse(t.contains("<"));
		assertFalse(t.contains(">"));
	}

	@Test
	public void keysAreStableAcrossReparse()
	{
		String k1 = new JsonGuideParser().parse(SAMPLE).getEpisodes().get(0).getBanks().get(0).getSteps().get(0).getKey();
		String k2 = new JsonGuideParser().parse(SAMPLE).getEpisodes().get(0).getBanks().get(0).getSteps().get(0).getKey();
		assertEquals(k1, k2);
	}
}
