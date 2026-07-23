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

	@Test
	public void splitsParagraphStepsIntoOneStepPerSentence()
	{
		Guide g = new JsonGuideParser().parse("{\"chapters\":[{\"sections\":[{\"steps\":["
			+ "{\"content\":[{\"text\":\"First action here. Second action here.\"}],"
			+ "\"metadata\":{\"items_needed\":\"knife, logs\"}}"
			+ "]}]}]}");
		GuideBank sec = g.getEpisodes().get(0).getBanks().get(0);
		assertEquals(2, sec.getSteps().size());
		// the items suffix rides the FIRST fragment only
		assertEquals("First action here. (Items: knife, logs)", sec.getSteps().get(0).getText());
		assertEquals("Second action here.", sec.getSteps().get(1).getText());
	}

	@Test
	public void neverSplitsInsideParenthesesOrAfterAbbreviations()
	{
		assertEquals(2, JsonGuideParser.splitDigestible(
			"Pickpocket men (hop worlds. Really). Fletch the logs afterwards.").size());
		assertEquals(2, JsonGuideParser.splitDigestible(
			"Use 2t oaks, see e.g. Getting Rats for the method. Continue to 35.").size());
	}

	@Test
	public void videoLinkAttachesToTheFragmentContainingItsAnchor()
	{
		Guide g = new JsonGuideParser().parse("{\"chapters\":[{\"sections\":[{\"steps\":["
			+ "{\"content\":["
			+ "{\"text\":\"Train woodcutting to 35 first. \"},"
			+ "{\"text\":\"Getting Rats for 2t Oaks\",\"url\":\"https://www.youtube.com/watch?v=3ysPgln5qZ4\",\"isLink\":true},"
			+ "{\"text\":\" shows the method. Then bank everything you have.\"}"
			+ "]}"
			+ "]}]}]}");
		GuideBank sec = g.getEpisodes().get(0).getBanks().get(0);
		int withVideo = 0;
		for (GuideStep s : sec.getSteps())
		{
			if (s.getVideoUrl() != null)
			{
				withVideo++;
				assertTrue(s.getText().contains("Getting Rats"));
				assertEquals("https://www.youtube.com/watch?v=3ysPgln5qZ4", s.getVideoUrl());
			}
		}
		assertEquals(1, withVideo);
	}

	@Test
	public void nonVideoUrlsNeverBecomeLinks()
	{
		Guide g = new JsonGuideParser().parse("{\"chapters\":[{\"sections\":[{\"steps\":["
			+ "{\"content\":[{\"text\":\"Wiki map reference for the route.\","
			+ "\"formatting\":{\"url\":\"https://oldschool.runescape.wiki/w/Map\",\"isLink\":true}}]}"
			+ "]}]}]}");
		assertTrue(g.getEpisodes().get(0).getBanks().get(0).getSteps().get(0).getVideoUrl() == null);
	}

	@Test
	public void oldParagraphKeyMapsToAllSplitChildren()
	{
		Guide g = new JsonGuideParser().parse("{\"chapters\":[{\"sections\":[{\"steps\":["
			+ "{\"content\":[{\"text\":\"First action here. Second action here.\"}]}"
			+ "]}]}]}");
		String oldText = "First action here. Second action here.";
		String oldKey = "C1.S1#" + Integer.toHexString(oldText.hashCode()) + "#0";
		GuideBank sec = g.getEpisodes().get(0).getBanks().get(0);
		java.util.List<String> children = g.getLegacySplitKeys().get(oldKey);
		assertEquals(2, children.size());
		assertEquals(sec.getSteps().get(0).getKey(), children.get(0));
		assertEquals(sec.getSteps().get(1).getKey(), children.get(1));
	}
}
