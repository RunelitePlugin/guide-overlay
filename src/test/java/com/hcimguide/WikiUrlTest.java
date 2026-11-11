package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WikiUrlTest
{
	@Test
	public void extractsTitlesFromLinks()
	{
		assertEquals("Guide:B0aty_HCIM_Guide_V3",
			WikiUrl.pageTitle("https://oldschool.runescape.wiki/w/Guide:B0aty_HCIM_Guide_V3"));
		assertEquals("Guide:Some_Guide",
			WikiUrl.pageTitle("https://oldschool.runescape.wiki/w/Guide:Some_Guide?veaction=edit#Bank_3"));
		assertEquals("Guide:Test_Guide",
			WikiUrl.pageTitle("https://oldschool.runescape.wiki/w/Guide%3ATest_Guide"));
		assertEquals("Ironman_Guide",
			WikiUrl.pageTitle("https://oldschool.runescape.wiki/wiki/Ironman_Guide"));
		assertEquals("Guide:My_Guide", WikiUrl.pageTitle("Guide:My Guide"));
	}

	@Test
	public void displayNameDropsNamespaceAndUnderscores()
	{
		assertEquals("B0aty HCIM Guide V3", WikiUrl.displayName("Guide:B0aty_HCIM_Guide_V3"));
		assertEquals("Ironman Guide", WikiUrl.displayName("Ironman_Guide"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsForeignHosts()
	{
		WikiUrl.pageTitle("https://evil.example.com/w/Guide:X");
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsSimilarButWrongWiki()
	{
		WikiUrl.pageTitle("https://runescape.wiki/w/X");
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsEmpty()
	{
		WikiUrl.pageTitle("  ");
	}

	@Test
	public void titlesWithPercentValidateAsStoredWithoutDecodingAgain()
	{
		// what a link yields is what the registry stores, and what it stores
		// must validate as that exact string on every later read
		String literalPercent = WikiUrl.pageTitle("https://oldschool.runescape.wiki/w/Guide:100%25_run");
		assertEquals("Guide:100%_run", literalPercent);
		assertTrue(WikiUrl.isValidTitle(literalPercent));

		String looksEncoded = WikiUrl.pageTitle("https://oldschool.runescape.wiki/w/Guide:A%2541");
		assertEquals("Guide:A%41", looksEncoded);
		assertTrue(WikiUrl.isValidTitle(looksEncoded));

		String schemeLike = WikiUrl.pageTitle("https://oldschool.runescape.wiki/w/Foo%3A%2F%2Fbar");
		assertEquals("Foo://bar", schemeLike);
		assertTrue(WikiUrl.isValidTitle(schemeLike));
	}

	@Test
	public void storedTitleValidationKeepsEveryStructuralRule()
	{
		assertTrue(WikiUrl.isValidTitle("Guide:B0aty_HCIM_Guide_V3"));
		assertFalse(WikiUrl.isValidTitle(null));
		assertFalse(WikiUrl.isValidTitle(""));
		assertFalse(WikiUrl.isValidTitle("Has space"));
		assertFalse(WikiUrl.isValidTitle(" Padded"));
		assertFalse(WikiUrl.isValidTitle("<html>x"));
		assertFalse(WikiUrl.isValidTitle("a|b"));
		assertFalse(WikiUrl.isValidTitle("a[b]"));
		assertFalse(WikiUrl.isValidTitle("a{b}"));
		StringBuilder tooLong = new StringBuilder();
		for (int i = 0; i < 256; i++)
		{
			tooLong.append('a');
		}
		assertFalse(WikiUrl.isValidTitle(tooLong.toString()));
	}

	@Test
	public void bareTitleKeepsALiteralPercent()
	{
		assertEquals("Guide:100%_run", WikiUrl.pageTitle("Guide:100% run"));
		assertEquals("Guide:Bob's_Guide", WikiUrl.pageTitle("Guide:Bob%27s_Guide"));
	}

	@Test
	public void malformedEscapeInALinkIsRejectedWithAPlainMessage()
	{
		try
		{
			WikiUrl.pageTitle("https://oldschool.runescape.wiki/w/Guide:100%_run");
			org.junit.Assert.fail("a malformed escape in a link must be rejected");
		}
		catch (IllegalArgumentException e)
		{
			assertEquals("Not a valid wiki link", e.getMessage());
		}
	}
}
