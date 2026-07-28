package com.hcimguide;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.Text;

/**
 * Draws the step-related highlights, similar to Quest Helper:
 * - the guided (current or pinned) step's target NPC: thick outline, name and
 *   colored target arrow
 * - every NPC referenced by the active bank's unchecked steps: thinner outline
 * - ground items the active bank needs: tile highlight + item name
 */
public class TargetOverlay extends Overlay
{
	private final Client client;
	private final HcimGuidePlugin plugin;
	private final HcimGuideConfig config;
	private final ModelOutlineRenderer outlineRenderer;

	@Inject
	public TargetOverlay(Client client, HcimGuidePlugin plugin, HcimGuideConfig config, ModelOutlineRenderer outlineRenderer)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.outlineRenderer = outlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		OverlayFonts.apply(graphics, config);
		if (!plugin.allowSceneGuidance())
		{
			return null;
		}
		NPC target = plugin.getTargetNpc();

		// step NPCs (skip the pinned target; it gets its own stronger outline below)
		if (config.highlightStepNpcs())
		{
			Color color = config.stepNpcColor();
			for (NPC npc : plugin.getStepNpcs())
			{
				if (npc == null || npc == target)
				{
					continue;
				}
				outlineRenderer.drawOutline(npc, 1, color, 1);
				drawNameAbove(graphics, npc, color);
			}
		}

		// the current step's applicable scene objects (nearest ladder/altar/
		// door of each type the step mentions - scoped by the plugin)
		if (config.highlightStepObjects())
		{
			Color color = config.stepObjectColor();
			Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 28);
			int plane = client.getTopLevelWorldView().getPlane();
			for (net.runelite.api.TileObject obj : plugin.getObjectHighlights())
			{
				// spawn events fire for ALL planes at scene load - never draw
				// a floating outline for a ladder on another floor
				if (obj == null || obj.getPlane() != plane)
				{
					continue;
				}
				java.awt.Shape box = obj.getClickbox();
				if (box == null)
				{
					continue;
				}
				graphics.setColor(fill);
				graphics.fill(box);
				graphics.setColor(color);
				graphics.draw(box);
			}
		}

		// needed ground items
		if (config.highlightGroundItems())
		{
			Color color = config.groundItemColor();
			for (GroundHighlight g : plugin.getGroundHighlights())
			{
				LocalPoint lp = g.getTile().getLocalLocation();
				if (lp == null)
				{
					continue;
				}
				Polygon poly = Perspective.getCanvasTilePoly(client, lp);
				if (poly != null)
				{
					OverlayUtil.renderPolygon(graphics, poly, color);
				}
				Point textLoc = Perspective.getCanvasTextLocation(client, graphics, lp, g.getName(), 0);
				if (textLoc != null)
				{
					OverlayUtil.renderTextLocation(graphics, textLoc, g.getName(), color);
				}
			}
		}

		// pinned target on top
		if (target != null)
		{
			Color color = config.highlightColor();
			outlineRenderer.drawOutline(target, 2, color, 2);
			drawNameAbove(graphics, target, color);
		}

		drawTargetArrow(graphics, target);
		return null;
	}

	private void drawTargetArrow(Graphics2D graphics, NPC npc)
	{
		if (!config.enableHintArrow())
		{
			return;
		}
		int centerX;
		int tipY;
		Shape hull = npc == null ? null : npc.getConvexHull();
		if (hull != null)
		{
			Rectangle bounds = hull.getBounds();
			centerX = bounds.x + bounds.width / 2;
			tipY = bounds.y - 4;
		}
		else
		{
			net.runelite.api.coords.WorldPoint point = plugin.getTargetArrowPoint();
			if (point == null)
			{
				return;
			}
			LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), point);
			if (local == null)
			{
				return;
			}
			Polygon tile = Perspective.getCanvasTilePoly(client, local);
			if (tile == null)
			{
				return;
			}
			Rectangle bounds = tile.getBounds();
			centerX = bounds.x + bounds.width / 2;
			tipY = bounds.y - 3;
		}

		int size = config.targetArrowSize();
		Polygon arrow = targetArrowPolygon(centerX, tipY, size);
		Graphics2D arrowGraphics = (Graphics2D) graphics.create();
		try
		{
			arrowGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			arrowGraphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
				BasicStroke.JOIN_ROUND));
			arrowGraphics.setColor(config.targetArrowColor());
			arrowGraphics.fillPolygon(arrow);
			arrowGraphics.setColor(new Color(0, 0, 0,
				Math.min(220, config.targetArrowColor().getAlpha())));
			arrowGraphics.drawPolygon(arrow);
		}
		finally
		{
			arrowGraphics.dispose();
		}
	}

	static Polygon targetArrowPolygon(int centerX, int tipY, int size)
	{
		int half = Math.max(3, size / 2);
		int top = tipY - Math.max(6, size);
		Polygon arrow = new Polygon();
		arrow.addPoint(centerX - half, top);
		arrow.addPoint(centerX + half, top);
		arrow.addPoint(centerX, tipY);
		return arrow;
	}

	private static void drawNameAbove(Graphics2D graphics, NPC npc, Color color)
	{
		String rawName = npc.getName();
		if (rawName == null)
		{
			return;
		}
		String name = Text.removeTags(rawName);
		Point textLoc = npc.getCanvasTextLocation(graphics, name, npc.getLogicalHeight() + 40);
		if (textLoc != null)
		{
			OverlayUtil.renderTextLocation(graphics, textLoc, name, color);
		}
	}
}
