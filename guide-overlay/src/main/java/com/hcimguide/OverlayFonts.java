package com.hcimguide;

import java.awt.Graphics2D;
import net.runelite.client.ui.FontManager;

/**
 * Applies the user's overlay font choice consistently across all of this
 * plugin's overlays. CLIENT_DEFAULT leaves the font RuneLite already set,
 * so the plugin follows the client-wide overlay font setting.
 */
final class OverlayFonts
{
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
			case CLIENT_DEFAULT:
			default:
				// keep whatever RuneLite set
				break;
		}
	}
}
