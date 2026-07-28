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
 * Compass widget pointing toward the exact destination currently handed to
 * Shortest Path (relative to the current camera rotation), with an optional
 * tile distance underneath. This keeps the compass present for every located
 * step using Shortest Path, even when its destination is inside the loaded
 * scene. Without an active Shortest Path hand-off it falls back to the tracked
 * far target, then to the next unchecked step's known location when configured.
 * Dial and needle style are configurable (full dial or bare arrow; triangle
 * or tailed).
 *
 * Deliberately unobtrusive: small by default, semi-transparent (opacity
 * configurable), and movable anywhere on screen with Alt+drag like any
 * RuneLite overlay. Cheap: a handful of trig ops and one polygon per frame.
 */
public class DirectionArrowOverlay extends Overlay
{
	private static final int TEXT_HEIGHT = 14;

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
		// Prefer the exact tile already accepted by the Shortest Path hand-off.
		// In particular, do not recompute from a live NPC here: the pathfinder
		// deliberately applies a movement deadband, and the compass must agree
		// with the path it actually drew rather than point a tile or two away.
		WorldPoint target = selectPrimaryTarget(
			plugin.getShortestPathTarget(), plugin.getFarTarget());
		// No active path/far target: fall back to the NEXT unchecked step's
		// known target (config-gated). The fallback remains limited to a target
		// outside the loaded scene or on another floor so nearby locations are
		// handled by the scene arrow unless Shortest Path is actively guiding
		// them.
		if (target == null && config.compassNextStep() && !plugin.hasPinnedTarget())
		{
			WorldPoint next = plugin.getNextStepPoint();
			if (next != null
				&& (next.getPlane() != player.getWorldLocation().getPlane()
					|| net.runelite.api.coords.LocalPoint.fromWorld(client.getTopLevelWorldView(), next) == null))
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
		// a target on another floor gets an explicit floor cue instead of a
		// misleading flat bearing to a spot the player may be standing on
		int planeDelta = target.getPlane() - me.getPlane();
		// standing ON the destination tile, same floor: there is no bearing to
		// draw (atan2(0,0) would render an arbitrary north needle over
		// "0 tiles"), and nothing useful to say - hide until there is one.
		// Reachable since the compass follows in-scene path targets.
		if (distance == 0 && planeDelta == 0)
		{
			return null;
		}

		// world bearing (0 = north, clockwise), then rotate into screen space
		// using the camera yaw. Current clients report yaw in 16384 JAU per
		// revolution (0 = north) - same convention Perspective.localToMinimap
		// uses, which is also where the "+" rotation sign comes from.
		double worldAngle = Math.atan2(dx, dy);
		double cameraRad = (client.getCameraYaw() & 0x3fff) * (Math.PI * 2 / 16384.0);
		double screenAngle = worldAngle + cameraRad;

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		OverlayFonts.apply(g, config);

		final int size = config.compassSize();
		final double opacity = config.compassOpacity() / 100.0;
		final boolean showDistance = config.compassShowDistance();

		int cx = size / 2;
		int cy = size / 2;
		int r = size / 2 - 3;
		// the compass has its own colour so it can be told apart from the NPC
		// outline, which is what highlightColor drives
		Color accent = withOpacity(config.compassColor(), opacity);

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
			String label;
			if (planeDelta != 0 && distance == 0)
			{
				// standing on the target's tile with the target on another
				// floor: a "0 tiles" bearing is meaningless - show only the
				// floor cue
				label = planeDelta > 0 ? "above" : "below";
			}
			else if (planeDelta != 0)
			{
				label = distance + " tiles " + (planeDelta > 0 ? "(above)" : "(below)");
			}
			else
			{
				label = distance + " tiles";
			}
			int w = g.getFontMetrics().stringWidth(label);
			int tx = Math.max(0, (size - w) / 2);
			g.setColor(withOpacity(Color.BLACK, opacity));
			g.drawString(label, tx + 1, size + TEXT_HEIGHT - 3 + 1);
			g.setColor(withOpacity(Color.WHITE, opacity));
			g.drawString(label, tx, size + TEXT_HEIGHT - 3);
		}

		return new Dimension(size, size + (showDistance ? TEXT_HEIGHT : 0));
	}

	/** Prefer the destination actually represented by Shortest Path. */
	static WorldPoint selectPrimaryTarget(WorldPoint shortestPathTarget, WorldPoint farTarget)
	{
		return shortestPathTarget != null ? shortestPathTarget : farTarget;
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
