package com.hcimguide;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Free-floating next/previous step arrow buttons (the "detached" mode of the
 * step navigation arrows - the attached mode renders inside {@link HudOverlay}).
 * Movable anywhere with Alt+drag like any RuneLite overlay.
 *
 * Rendering only stores the buttons' local rectangles; the actual click
 * handling lives in the plugin's mouse listener, which hit-tests against
 * {@link #hitArrow}. The overlay itself never synthesizes any input.
 */
public class StepNavOverlay extends Overlay
{
	static final int BUTTON_W = 28;
	static final int BUTTON_H = 22;
	static final int BUTTON_GAP = 4;

	private static final Color BACKGROUND = new Color(30, 30, 30, 170);
	private static final Color BORDER = new Color(140, 130, 110, 200);
	private static final Color ARROW = new Color(230, 220, 190);

	private final HcimGuidePlugin plugin;
	private final HcimGuideConfig config;

	/** Local (overlay-relative) button rects from the last frame; null = not shown. */
	private volatile Rectangle prevRect;
	private volatile Rectangle nextRect;

	@Inject
	public StepNavOverlay(HcimGuidePlugin plugin, HcimGuideConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.BOTTOM_LEFT);
		// clickable controls must never be covered by an open interface while
		// still eating clicks - render above widgets so what's clickable is
		// always exactly what's visible
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		// same right-click actions as the HUD box, for the floating mode
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Next step",
			"Guide Overlay", e -> plugin.navigateStep(true));
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Previous step",
			"Guide Overlay", e -> plugin.navigateStep(false));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (config.navArrows() != HcimGuideConfig.ArrowMode.FLOATING || !plugin.hasGuideLoaded())
		{
			prevRect = null;
			nextRect = null;
			return null;
		}

		Rectangle prev = new Rectangle(0, 0, BUTTON_W, BUTTON_H);
		Rectangle next = new Rectangle(BUTTON_W + BUTTON_GAP, 0, BUTTON_W, BUTTON_H);
		drawArrowButton(graphics, prev, false);
		drawArrowButton(graphics, next, true);
		prevRect = prev;
		nextRect = next;
		return new Dimension(BUTTON_W * 2 + BUTTON_GAP, BUTTON_H);
	}

	/**
	 * @param screen a click location in screen coordinates
	 * @return +1 when it hits the next-arrow, -1 for previous, 0 for neither
	 */
	int hitArrow(Point screen)
	{
		return hitArrow(screen, getBounds(), prevRect, nextRect);
	}

	/** Shared local-rect hit test used by both arrow hosts. */
	static int hitArrow(Point screen, Rectangle bounds, Rectangle prev, Rectangle next)
	{
		if (screen == null || bounds == null || bounds.width <= 0)
		{
			return 0;
		}
		int lx = screen.x - bounds.x;
		int ly = screen.y - bounds.y;
		if (next != null && next.contains(lx, ly))
		{
			return 1;
		}
		if (prev != null && prev.contains(lx, ly))
		{
			return -1;
		}
		return 0;
	}

	/** Rounded button with a solid triangle arrow; shared with the attached mode. */
	static void drawArrowButton(Graphics2D g, Rectangle r, boolean right)
	{
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(BACKGROUND);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(BORDER);
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 6, 6);

		int cx = r.x + r.width / 2;
		int cy = r.y + r.height / 2;
		int half = Math.min(r.width, r.height) / 4;
		int[] xs = right
			? new int[]{cx - half + 1, cx - half + 1, cx + half - 1}
			: new int[]{cx + half - 1, cx + half - 1, cx - half + 1};
		int[] ys = {cy - half, cy + half, cy};
		g.setColor(ARROW);
		g.fillPolygon(xs, ys, 3);
		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}
	}
}
