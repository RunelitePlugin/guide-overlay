package com.hcimguide;

import java.awt.Font;
import java.awt.Graphics2D;
import net.runelite.client.ui.FontManager;

/**
 * Applies the user's overlay font choice consistently across all of this
 * plugin's overlays. CLIENT_DEFAULT leaves the font RuneLite already set,
 * so the plugin follows the client-wide overlay font setting. The SANS_*
 * styles use the JVM's logical sans-serif font (always present on every
 * platform) for a plainer, more readable look than the RuneScape faces.
 */
final class OverlayFonts
{
	// cached: apply() runs on the render path every frame
	private static final Font SANS_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
	private static final Font SANS_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font SANS_LARGE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 14);

	private OverlayFonts()
	{
	}

	static void apply(Graphics2D g, HcimGuideConfig.FontStyle style)
	{
		switch (style)
		{
			case SMALL:
				g.setFont(FontManager.getRunescapeSmallFont());
				break;
			case REGULAR:
				g.setFont(FontManager.getRunescapeFont());
				break;
			case BOLD:
				g.setFont(FontManager.getRunescapeBoldFont());
				break;
			case SANS_SMALL:
				g.setFont(SANS_SMALL_FONT);
				break;
			case SANS:
				g.setFont(SANS_FONT);
				break;
			case SANS_LARGE:
				g.setFont(SANS_LARGE_FONT);
				break;
			case CLIENT_DEFAULT:
			default:
				// keep whatever RuneLite set
				break;
		}
	}
}
