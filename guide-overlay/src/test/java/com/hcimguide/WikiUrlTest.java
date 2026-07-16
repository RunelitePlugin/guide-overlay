package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
}
