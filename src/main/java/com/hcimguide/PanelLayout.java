package com.hcimguide;

/** Pure layout arithmetic kept separate from Swing so it can be regression tested. */
final class PanelLayout
{
	private PanelLayout()
	{
	}

	/**
	 * RuneLite's own settings-panel content margins (ConfigPanel uses
	 * {@code new EmptyBorder(8, 10, 10, 10)} around its scrollable content).
	 * The checklist adopts them verbatim so the plugin's sidebar and the
	 * client's settings sidebar keep identical edge geometry.
	 */
	static final int CONTENT_MARGIN_TOP = 8;
	static final int CONTENT_MARGIN_LEFT = 10;
	static final int CONTENT_MARGIN_BOTTOM = 10;
	static final int CONTENT_MARGIN_RIGHT = 10;

	/**
	 * Small internal gap inside the step-text component, on top of the 10px
	 * content margin, producing a 12px visible clearance between the wrap
	 * boundary and the divider/scrollbar.
	 */
	static final int TEXT_RIGHT_SAFETY = 2;

	/** RuneLite's fixed plugin-panel content width (PluginPanel.PANEL_WIDTH). */
	static final int PANEL_CONTENT_WIDTH = 225;

	/**
	 * Conservative step-text width for the very first preferred-size pass,
	 * before the layout has allocated the text component a real width: the
	 * fixed content width minus both content margins, the deepest row border
	 * (capped indent plus right edge), a checkbox column and an east-control
	 * column. Deliberately NARROW - narrower than ANY real row, so the first
	 * guess only overestimates height for one pass; a too-wide guess would
	 * underestimate it and clip the last line until the resize settles.
	 */
	static int preLayoutTextWidth()
	{
		return PANEL_CONTENT_WIDTH - CONTENT_MARGIN_LEFT - CONTENT_MARGIN_RIGHT
			- 24 /* deepest capped row border: 6+2*8 indent + 2 right */
			- 22 /* checkbox column */ - 28 /* east controls */;
	}

	/**
	 * Vertical view position that centers a row in the viewport.
	 *
	 * @param rowY row top in view coordinates
	 * @param rowHeight row height
	 * @param viewportHeight the viewport's visible extent height
	 * @param viewHeight total scrollable view height
	 * @return clamped y for {@code JViewport.setViewPosition}; a row taller
	 *     than the viewport aligns its top so its start is always visible
	 */
	static int centeredViewPosition(int rowY, int rowHeight, int viewportHeight, int viewHeight)
	{
		int desired = rowHeight >= viewportHeight
			? rowY
			: rowY - (viewportHeight - rowHeight) / 2;
		int max = Math.max(0, viewHeight - viewportHeight);
		return Math.max(0, Math.min(desired, max));
	}
}
