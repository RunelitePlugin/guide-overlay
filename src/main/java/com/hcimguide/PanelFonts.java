package com.hcimguide;

import java.awt.Font;

/**
 * Side-panel font resolution. A thin wrapper over the shared bounded
 * {@link OverlayFonts} resolver so the panel and the overlays cannot drift
 * apart: same family choices, same custom-family sanitization, same
 * unavailable-family fallback, same LRU bound and shutdown clear.
 */
final class PanelFonts
{
	private PanelFonts()
	{
	}

	/** The configured side-panel font, resolved through the shared resolver. */
	static Font resolve(Font fallback, HcimGuideConfig config)
	{
		return OverlayFonts.resolve(fallback, config.panelFontFamily(),
			config.customPanelFontFamily(), config.panelFontWeight(), config.panelFontSize());
	}

	/**
	 * Drop cached fonts. The cache is shared with the overlays; the plugin's
	 * shutDown clears it once through either entry point.
	 */
	static void clear()
	{
		OverlayFonts.clear();
	}
}
