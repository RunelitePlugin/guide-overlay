package com.hcimguide;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Compass widget shown when the tracked target is known but too far away to
 * be in the loaded scene: an arrow pointing toward the target (relative to
 * the current camera rotation) with an optional tile distance underneath.
 * Points at the pinned step's target; with nothing pinned it falls back to
 * the next unchecked step's known location (config-gated), so the compass
 * is there whenever the guide knows where you're headed. Dial and needle
 * style are configurable (full dial or bare arrow; triangle or tailed).
 *
 * Deliberately unobtrusive: small by default, semi-transparent (opacity
 * configurable), only visible while a far-away target is active, and movable
 * anywhere on screen with Alt+drag like any RuneLite overlay. Cheap: a
 * handful of trig ops and one polygon per frame.
 */
public class DirectionArrowOverlay extends Overlay
{
	private static final int TEXT_HEIGHT = 14;

	/**
	 * The next-step fallback only engages beyond this 2D tile distance:
	 * within it, an out-of-scene result usually means a plane mismatch
	 * (target upstairs), where a compass would just point at the floor.
	 */
	private static final int MIN_FALLBACK_DISTANCE = 40;

	private final Client client;
	private final HcimGuidePlugin plugin;
	private final HcimGuideConfig config;

	@Inject
	public DirectionArrowOverlay(Client client, HcimGuidePlugin plugin, HcimGuideConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showDirectionArrow())
		{
			return null;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null; // checked FIRST: the fallback below must not run without a player
		}
		WorldPoint target = plugin.getFarTarget();
		// nothing pinned: fall back to the NEXT unchecked step's known target
		// (config-gated), but only when it's genuinely far - nearby targets are
		// already handled by highlights and the hint arrow, and fromWorld also
		// returns null for a mere PLANE mismatch (target upstairs), where a
		// "2 tiles north" compass would mislead. Never while something IS
		// pinned: the next-step point isn't refreshed then.
		if (target == null && config.compassNextStep() && !plugin.hasPinnedTarget())
		{
			WorldPoint next = plugin.getNextStepPoint();
			if (next != null
				&& next.distanceTo2D(player.getWorldLocation()) > MIN_FALLBACK_DISTANCE
				&& net.runelite.api.coords.LocalPoint.fromWorld(client.getTopLevelWorldView(), next) == null)
			{
				target = next;
			}
		}
		if (target == null || !plugin.allowCompassGuidance(target))
		{
			return null;
		}
		WorldPoint me = player.getWorldLocation();
		int dx = target.getX() - me.getX();
		int dy = target.getY() - me.getY();
		int distance = (int) Math.round(Math.hypot(dx, dy));

		// world bearing (0 = north, clockwise), then rotate into screen space
		// using the camera yaw. Current clients report yaw in 16384 JAU per
		// revolution (0 = north) - same convention Perspective.localToMinimap
		// uses, which is also where the "+" rotation sign comes from.
		double worldAngle = Math.atan2(dx, dy);
		double cameraRad = (client.getCameraYaw() & 0x3fff) * (Math.PI * 2 / 16384.0);
		double screenAngle = worldAngle + cameraRad;

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		OverlayFonts.apply(g, config.overlayFontStyle());

		final int size = config.compassSize();
		final double opacity = config.compassOpacity() / 100.0;
		final boolean showDistance = config.compassShowDistance();

		int cx = size / 2;
		int cy = size / 2;
		int r = size / 2 - 3;
		Color accent = withOpacity(config.highlightColor(), opacity);

		if (config.compassShowRing())
		{
			g.setColor(withOpacity(new Color(0, 0, 0, 140), opacity));
			g.fillOval(cx - r, cy - r, r * 2, r * 2);
			g.setColor(accent);
			g.setStroke(new BasicStroke(1.5f));
			g.drawOval(cx - r, cy - r, r * 2, r * 2);
		}

		g.setColor(accent);
		int tipX = cx + (int) Math.round(Math.sin(screenAngle) * (r - 4));
		int tipY = cy - (int) Math.round(Math.cos(screenAngle) * (r - 4));
		if (config.compassArrowStyle() == HcimGuideConfig.CompassArrowStyle.TAILED)
		{
			// arrow with a tail: shaft from the back of the dial to the tip,
			// capped by a smaller head so it reads as a drawn arrow
			int tailX = cx - (int) Math.round(Math.sin(screenAngle) * (r - 5));
			int tailY = cy + (int) Math.round(Math.cos(screenAngle) * (r - 5));
			g.setStroke(new BasicStroke(Math.max(2f, size / 22f),
				BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(tailX, tailY, tipX, tipY);
			drawHead(g, tipX, tipY, screenAngle, Math.max(6, size / 5));
		}
		else
		{
			// solid triangle: tip at the dial edge, base corners rotated +-150 degrees
			drawHead(g, tipX, tipY, screenAngle, Math.max(8, size / 4));
		}

		if (showDistance)
		{
			String label = distance + " tiles";
			int w = g.getFontMetrics().stringWidth(label);
			int tx = Math.max(0, (size - w) / 2);
			g.setColor(withOpacity(Color.BLACK, opacity));
			g.drawString(label, tx + 1, size + TEXT_HEIGHT - 3 + 1);
			g.setColor(withOpacity(Color.WHITE, opacity));
			g.drawString(label, tx, size + TEXT_HEIGHT - 3);
		}

		return new Dimension(size, size + (showDistance ? TEXT_HEIGHT : 0));
	}

	/** Filled triangular arrowhead with its tip at (tipX, tipY). */
	private static void drawHead(Graphics2D g, int tipX, int tipY, double angle, int arm)
	{
		double left = angle + Math.toRadians(150);
		double right = angle - Math.toRadians(150);
		Polygon tri = new Polygon();
		tri.addPoint(tipX, tipY);
		tri.addPoint(tipX + (int) Math.round(Math.sin(left) * arm), tipY - (int) Math.round(Math.cos(left) * arm));
		tri.addPoint(tipX + (int) Math.round(Math.sin(right) * arm), tipY - (int) Math.round(Math.cos(right) * arm));
		g.fillPolygon(tri);
	}

	private static Color withOpacity(Color c, double opacity)
	{
		int alpha = (int) Math.max(0, Math.min(255, Math.round(c.getAlpha() * opacity)));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
	}
}
