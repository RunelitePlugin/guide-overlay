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

/** Floating previous/next step, waypoint, and location controls. */
public class StepNavOverlay extends Overlay
{
	static final int BUTTON_W = 28;
	static final int BUTTON_H = 22;
	static final int BUTTON_GAP = 4;

	private static final Color BACKGROUND = new Color(30, 30, 30, 170);
	private static final Color BORDER = new Color(140, 130, 110, 200);
	private static final Color ARROW = new Color(230, 220, 190);
	private static final Color WAYPOINT = new Color(255, 200, 87);
	private static final Color LOCATION = new Color(110, 200, 255);
	private static final Color LOCATION_HIDDEN = new Color(205, 120, 120);

	private final HcimGuidePlugin plugin;
	private final HcimGuideConfig config;

	private volatile Rectangle prevRect;
	private volatile Rectangle prevWaypointRect;
	private volatile Rectangle locationRect;
	private volatile Rectangle nextWaypointRect;
	private volatile Rectangle nextRect;

	@Inject
	public StepNavOverlay(HcimGuidePlugin plugin, HcimGuideConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Next step",
			"Guide Overlay", e -> plugin.navigateStep(true));
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Previous step",
			"Guide Overlay", e -> plugin.navigateStep(false));
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Next waypoint",
			"Guide Overlay", e -> plugin.navigateWaypoint(1));
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Previous waypoint",
			"Guide Overlay", e -> plugin.navigateWaypoint(-1));
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Toggle location guide",
			"Guide Overlay", e -> plugin.toggleLocationGuideForCurrentStep());
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Snooze location guide (5 min)",
			"Guide Overlay", e -> plugin.snoozeLocationGuide());
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Restore location guide",
			"Guide Overlay", e -> plugin.restoreLocationGuide());
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Set current tile as pin",
			"Guide Overlay", e -> plugin.setCurrentTileAsCustomPin(false));
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Add current tile as waypoint",
			"Guide Overlay", e -> plugin.setCurrentTileAsCustomPin(true));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (config.navArrows() != HcimGuideConfig.ArrowMode.FLOATING || !plugin.hasGuideLoaded())
		{
			clearRects();
			return null;
		}

		boolean showLocation = plugin.hasLocationForGuidedStep();
		boolean showWaypoints = plugin.hasMultipleWaypointsForGuidedStep();
		int x = 0;
		Rectangle prev = buttonAt(x);
		x += BUTTON_W + BUTTON_GAP;
		Rectangle prevWaypoint = showWaypoints ? buttonAt(x) : null;
		if (showWaypoints)
		{
			x += BUTTON_W + BUTTON_GAP;
		}
		Rectangle location = showLocation ? buttonAt(x) : null;
		if (showLocation)
		{
			x += BUTTON_W + BUTTON_GAP;
		}
		Rectangle nextWaypoint = showWaypoints ? buttonAt(x) : null;
		if (showWaypoints)
		{
			x += BUTTON_W + BUTTON_GAP;
		}
		Rectangle next = buttonAt(x);

		drawArrowButton(graphics, prev, false);
		if (prevWaypoint != null)
		{
			drawWaypointButton(graphics, prevWaypoint, false);
		}
		if (location != null)
		{
			drawLocationButton(graphics, location, plugin.isLocationGuideHiddenForGuidedStep());
		}
		if (nextWaypoint != null)
		{
			drawWaypointButton(graphics, nextWaypoint, true);
		}
		drawArrowButton(graphics, next, true);

		prevRect = prev;
		prevWaypointRect = prevWaypoint;
		locationRect = location;
		nextWaypointRect = nextWaypoint;
		nextRect = next;
		return new Dimension(totalWidth(showLocation, showWaypoints), BUTTON_H);
	}

	private static Rectangle buttonAt(int x)
	{
		return new Rectangle(x, 0, BUTTON_W, BUTTON_H);
	}

	private void clearRects()
	{
		prevRect = null;
		prevWaypointRect = null;
		locationRect = null;
		nextWaypointRect = null;
		nextRect = null;
	}

	int hitArrow(Point screen)
	{
		return hitArrow(screen, getBounds(), prevRect, nextRect);
	}

	int hitWaypointArrow(Point screen)
	{
		return hitArrow(screen, getBounds(), prevWaypointRect, nextWaypointRect);
	}

	boolean hitLocationToggle(Point screen)
	{
		return hitButton(screen, getBounds(), locationRect);
	}

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

	static boolean hitButton(Point screen, Rectangle bounds, Rectangle button)
	{
		return screen != null && bounds != null && bounds.width > 0 && button != null
			&& button.contains(screen.x - bounds.x, screen.y - bounds.y);
	}

	static int totalWidth(boolean includeLocation)
	{
		return totalWidth(includeLocation, false);
	}

	static int totalWidth(boolean includeLocation, boolean includeWaypoints)
	{
		int buttons = 2 + (includeLocation ? 1 : 0) + (includeWaypoints ? 2 : 0);
		return BUTTON_W * buttons + BUTTON_GAP * (buttons - 1);
	}

	static void drawArrowButton(Graphics2D g, Rectangle r, boolean right)
	{
		drawBase(g, r);
		drawTriangle(g, r, right, ARROW);
	}

	static void drawWaypointButton(Graphics2D g, Rectangle r, boolean right)
	{
		drawBase(g, r);
		drawTriangle(g, r, right, WAYPOINT);
		g.setColor(WAYPOINT);
		int lineX = right ? r.x + 5 : r.x + r.width - 6;
		g.drawLine(lineX, r.y + 6, lineX, r.y + r.height - 7);
	}

	private static void drawBase(Graphics2D g, Rectangle r)
	{
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(BACKGROUND);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(BORDER);
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 6, 6);
		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}
	}

	private static void drawTriangle(Graphics2D g, Rectangle r, boolean right, Color color)
	{
		int cx = r.x + r.width / 2;
		int cy = r.y + r.height / 2;
		int half = Math.min(r.width, r.height) / 4;
		int[] xs = right
			? new int[]{cx - half + 1, cx - half + 1, cx + half - 1}
			: new int[]{cx + half - 1, cx + half - 1, cx - half + 1};
		int[] ys = {cy - half, cy + half, cy};
		g.setColor(color);
		g.fillPolygon(xs, ys, 3);
	}

	static void drawLocationButton(Graphics2D g, Rectangle r, boolean hidden)
	{
		drawBase(g, r);
		int cx = r.x + r.width / 2;
		int cy = r.y + r.height / 2;
		int radius = Math.max(4, Math.min(r.width, r.height) / 4);
		g.setColor(hidden ? LOCATION_HIDDEN : LOCATION);
		g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
		g.drawLine(cx, cy - radius - 3, cx, cy + radius + 3);
		g.drawLine(cx - radius - 3, cy, cx + radius + 3, cy);
		if (hidden)
		{
			g.drawLine(cx - radius - 4, cy + radius + 4, cx + radius + 4, cy - radius - 4);
		}
	}
}
