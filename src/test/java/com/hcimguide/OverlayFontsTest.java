package com.hcimguide;

import java.awt.Font;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OverlayFontsTest
{
	@After
	public void clearCache()
	{
		OverlayFonts.clear();
	}

	@Test
	public void familyWeightAndSizeAreIndependent()
	{
		Font font = OverlayFonts.resolve(new Font(Font.SANS_SERIF, Font.PLAIN, 12),
			HcimGuideConfig.OverlayFontFamily.MONOSPACED, "",
			HcimGuideConfig.OverlayFontWeight.BOLD_ITALIC, 23);

		assertEquals(Font.MONOSPACED, font.getFamily());
		assertEquals(Font.BOLD | Font.ITALIC, font.getStyle());
		assertEquals(23, font.getSize());
	}

	@Test
	public void unavailableCustomFamilyFallsBackSafely()
	{
		Font font = OverlayFonts.resolve(new Font(Font.SERIF, Font.PLAIN, 12),
			HcimGuideConfig.OverlayFontFamily.CUSTOM,
			"Guide Overlay Definitely Missing Font 8f0c7b", HcimGuideConfig.OverlayFontWeight.PLAIN, 17);

		assertEquals(Font.SANS_SERIF, font.getFamily());
		assertEquals(17, font.getSize());
	}

	@Test
	public void legacySansChoiceUsesNewIndependentSize()
	{
		Font font = OverlayFonts.resolve(null,
			HcimGuideConfig.OverlayFontFamily.SANS_SMALL, "",
			HcimGuideConfig.OverlayFontWeight.FAMILY_DEFAULT, 31);

		assertEquals(Font.SANS_SERIF, font.getFamily());
		assertEquals(31, font.getSize());
	}

	@Test
	public void requestedSizeIsClampedToConfigBounds()
	{
		Font tiny = OverlayFonts.resolve(null,
			HcimGuideConfig.OverlayFontFamily.SANS_SERIF, "",
			HcimGuideConfig.OverlayFontWeight.PLAIN, 1);
		Font huge = OverlayFonts.resolve(null,
			HcimGuideConfig.OverlayFontFamily.SANS_SERIF, "",
			HcimGuideConfig.OverlayFontWeight.PLAIN, 999);

		assertEquals(8, tiny.getSize());
		assertEquals(40, huge.getSize());
	}

	@Test
	public void cacheIsBoundedAndClearable()
	{
		for (int size = 8; size <= 40; size++)
		{
			for (HcimGuideConfig.OverlayFontWeight weight : HcimGuideConfig.OverlayFontWeight.values())
			{
				OverlayFonts.resolve(null, HcimGuideConfig.OverlayFontFamily.CUSTOM,
					"Missing font " + size + '-' + weight.name(), weight, size);
			}
		}

		assertTrue(OverlayFonts.cacheSizeForTesting() <= 128);
		OverlayFonts.clear();
		assertEquals(0, OverlayFonts.cacheSizeForTesting());
	}
	@Test
	public void clientDefaultPreservesFallbackStyleUnlessOverridden()
	{
		Font fallback = new Font(Font.SERIF, Font.ITALIC, 14);
		Font inherited = OverlayFonts.resolve(fallback,
			HcimGuideConfig.OverlayFontFamily.CLIENT_DEFAULT, "",
			HcimGuideConfig.OverlayFontWeight.FAMILY_DEFAULT, 20);
		Font overridden = OverlayFonts.resolve(fallback,
			HcimGuideConfig.OverlayFontFamily.CLIENT_DEFAULT, "",
			HcimGuideConfig.OverlayFontWeight.BOLD, 20);

		assertEquals(Font.ITALIC, inherited.getStyle());
		assertEquals(Font.BOLD, overridden.getStyle());
	}

}
