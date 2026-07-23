package com.hcimguide;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Small movable on-screen panel showing the active bank and its next few
 * unchecked steps, so the sidebar doesn't need to stay open while playing.
 * Optionally extends below the panel with the current step's item pictures
 * and the attached next/previous step arrow buttons.
 *
 * Renders on the client thread; reads only the plugin's cached active bank
 * (O(1)) and iterates its ~30 steps, so per-frame cost is negligible. Item
 * images come from ItemManager's internal cache (ids are resolved off-thread
 * by the plugin when the current step changes, never during render).
 */
public class HudOverlay extends OverlayPanel
{
	private static final Color NEXT_STEP_COLOR = Color.WHITE;
	private static final Color LATER_STEP_COLOR = new Color(205, 205, 205);
	/** Section titles in warm orange so headers separate from body text at a glance. */
	private static final Color TITLE_COLOR = new Color(255, 172, 60);
	/** Teleport/route suggestions in light blue - distinct from the green trip-ready tick. */
	private static final Color ROUTE_COLOR = new Color(110, 200, 255);
	private static final Color TRIP_READY_COLOR = new Color(90, 240, 130);
	/** Near-black base for the box background; alpha comes from config. */
	private static final Color BG_BASE = new Color(16, 16, 16);
	private static final Color PRESENT = new Color(0, 200, 120, 200);
	private static final Color MISSING = new Color(190, 60, 60, 200);

	private static final int ICON_W = 36;
	private static final int ICON_H = 32;
	private static final int ICON_PAD = 2;
	private static final int MAX_ICON_ROWS = 2;
	private static final int STRIP_GAP = 2;
	/** Vertical gap between the current-step box and the "Next steps" box. */
	private static final int BOX_GAP = 4;

	private final HcimGuidePlugin plugin;
	private final HcimGuideConfig config;
	private final ItemManager itemManager;
	/** Second box for upcoming steps, so the current step stands alone. */
	private final PanelComponent nextStepsPanel = new PanelComponent();

	/** Local (overlay-relative) attached-arrow rects; null = not shown this frame. */
	private volatile Rectangle prevRect;
	private volatile Rectangle nextRect;

	@Inject
	public HudOverlay(HcimGuidePlugin plugin, HcimGuideConfig config, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
		// the box hosts CLICKABLE attached arrows: it must never sit invisibly
		// under an open interface (bank, map) while its arrow rects still eat
		// clicks - above-widgets keeps clickable == visible
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		// standard overlay right-click actions (callbacks run on the client
		// thread); all three only move checklist state - no game input
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Next step",
			"Guide Overlay", e -> plugin.navigateStep(true));
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Previous step",
			"Guide Overlay", e -> plugin.navigateStep(false));
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Pin next target",
			"Guide Overlay", e -> plugin.pinNextTrackableStep());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showHudOverlay())
		{
			clearArrowRects();
			return null;
		}
		GuideBank bank = plugin.getActiveBank();
		if (bank == null)
		{
			clearArrowRects();
			return null;
		}

		OverlayFonts.apply(graphics, config.overlayFontStyle());
		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(config.hudWidth(), 0));
		panelComponent.setBackgroundColor(backgroundColor());

		int[] progress = plugin.progressOf(bank.getSteps());
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(bank.getTitle() + "  (" + progress[0] + "/" + progress[1] + ")")
			.color(TITLE_COLOR)
			.build());

		int shown = 0;
		int max = config.hudMaxSteps();
		// with the split enabled, upcoming steps move to their own box below,
		// so the current step stands alone and is easy to focus on
		boolean split = config.hudSplitNextSteps() && max > 1;
		java.util.List<GuideStep> upcoming = split ? new java.util.ArrayList<>() : null;
		for (GuideStep step : bank.getSteps())
		{
			if (plugin.isStepDone(step.getKey()))
			{
				continue;
			}
			if (shown == 0 || !split)
			{
				// FULL text, never truncated: LineComponent word-wraps to the
				// panel width, so long steps grow the box instead of losing words
				panelComponent.getChildren().add(LineComponent.builder()
					.left((shown == 0 ? "> " : "- ") + step.getText())
					.leftColor(shown == 0 ? NEXT_STEP_COLOR : LATER_STEP_COLOR)
					.build());
			}
			else
			{
				upcoming.add(step);
			}
			if (++shown >= max)
			{
				break;
			}
		}

		// all of the section's items are in the inventory - good to go
		if (config.tripReadyIndicator() && plugin.isTripReady())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("✓ Trip ready")
				.leftColor(TRIP_READY_COLOR)
				.build());
		}

		// fastest-route hint (config-gated in the plugin; null = walk/nothing)
		String route = plugin.getRouteSuggestion();
		if (route != null && config.routeSuggestions())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("★ " + route)
				.leftColor(ROUTE_COLOR)
				.build());
		}

		Dimension d = super.render(graphics);
		if (d == null)
		{
			clearArrowRects();
			return null;
		}

		int y = d.height;
		if (config.hudShowStepItems())
		{
			y = drawItemStrip(graphics, d.width, y);
		}
		if (upcoming != null && !upcoming.isEmpty())
		{
			y = drawNextStepsBox(graphics, d.width, y, upcoming);
		}
		if (config.navArrows() == HcimGuideConfig.ArrowMode.ATTACHED)
		{
			y = drawAttachedArrows(graphics, d.width, y);
		}
		else
		{
			clearArrowRects();
		}
		return new Dimension(d.width, y);
	}

	/**
	 * The optional "Next steps" box, a visually separate panel under the
	 * current-step box (and its item strip). Word-wraps exactly like the main
	 * box and shares its width, font and background opacity.
	 */
	private int drawNextStepsBox(Graphics2D g, int width, int y, List<GuideStep> upcoming)
	{
		nextStepsPanel.getChildren().clear();
		// follow the MAIN box's actual rendered width (an Alt-resized overlay
		// overrides hudWidth), so the second box never escapes the bounds
		nextStepsPanel.setPreferredSize(new Dimension(width, 0));
		nextStepsPanel.setBackgroundColor(backgroundColor());
		nextStepsPanel.setPreferredLocation(new Point(0, y + BOX_GAP));
		nextStepsPanel.getChildren().add(TitleComponent.builder()
			.text("Next steps")
			.color(TITLE_COLOR)
			.build());
		for (GuideStep step : upcoming)
		{
			nextStepsPanel.getChildren().add(LineComponent.builder()
				.left("- " + step.getText())
				.leftColor(LATER_STEP_COLOR)
				.build());
		}
		// PanelComponent sizes its background (and return value) from the
		// PREVIOUS render's child measurements - stale whenever this box was
		// hidden last frame. A clipped measuring pass warms that cache so the
		// visible render below is correctly sized on its very first frame.
		Graphics2D measure = (Graphics2D) g.create();
		measure.setClip(new Rectangle(0, 0, 0, 0));
		nextStepsPanel.render(measure);
		measure.dispose();
		Dimension d = nextStepsPanel.render(g);
		return d == null ? y : y + BOX_GAP + d.height;
	}

	/**
	 * The current step's item pictures, drawn directly under the panel on the
	 * same translucent background so it reads as one box. Only ids the plugin
	 * already resolved are drawn; images come from ItemManager's cache and
	 * fill in over the next frames as they load.
	 */
	private int drawItemStrip(Graphics2D g, int width, int y)
	{
		HcimGuidePlugin.HudItems hud = plugin.getHudStepItems();
		if (hud == null || hud.ids == null)
		{
			return y;
		}
		List<ItemReq> items = hud.items;
		int[] ids = hud.ids;
		int resolved = 0;
		for (int id : ids)
		{
			if (id > 0)
			{
				resolved++;
			}
		}
		if (resolved == 0)
		{
			return y;
		}

		int perRow = Math.max(1, (width - ICON_PAD) / (ICON_W + ICON_PAD));
		int maxIcons = perRow * MAX_ICON_ROWS;
		int drawn = Math.min(resolved, maxIcons);
		int rows = (drawn + perRow - 1) / perRow;
		int stripH = rows * (ICON_H + ICON_PAD) + ICON_PAD;

		y += STRIP_GAP;
		g.setColor(backgroundColor());
		g.fillRect(0, y, width, stripH);

		boolean borders = config.itemPresenceBorders();
		InventorySnapshot inv = borders ? plugin.getInventorySnapshot() : null;
		int cell = 0;
		for (int i = 0; i < ids.length && cell < drawn; i++)
		{
			if (ids[i] <= 0)
			{
				continue;
			}
			int cx = ICON_PAD + (cell % perRow) * (ICON_W + ICON_PAD);
			int cy = y + ICON_PAD + (cell / perRow) * (ICON_H + ICON_PAD);
			ItemReq req = i < items.size() ? items.get(i) : null;
			int qty = req != null ? req.getQuantity() : 1;
			BufferedImage img = itemManager.getImage(ids[i], qty, qty > 1);
			if (img != null)
			{
				g.drawImage(img, cx, cy, null);
			}
			if (borders && req != null && inv != null)
			{
				g.setColor(inv.countOf(req) >= qty ? PRESENT : MISSING);
				g.drawRect(cx, cy, ICON_W - 1, ICON_H - 1);
			}
			cell++;
		}
		return y + stripH;
	}

	/** The attached ◀ ▶ buttons, centered under the box. */
	private int drawAttachedArrows(Graphics2D g, int width, int y)
	{
		y += STRIP_GAP;
		int totalW = StepNavOverlay.BUTTON_W * 2 + StepNavOverlay.BUTTON_GAP;
		int x = Math.max(0, (width - totalW) / 2);
		Rectangle prev = new Rectangle(x, y, StepNavOverlay.BUTTON_W, StepNavOverlay.BUTTON_H);
		Rectangle next = new Rectangle(x + StepNavOverlay.BUTTON_W + StepNavOverlay.BUTTON_GAP, y,
			StepNavOverlay.BUTTON_W, StepNavOverlay.BUTTON_H);
		StepNavOverlay.drawArrowButton(g, prev, false);
		StepNavOverlay.drawArrowButton(g, next, true);
		prevRect = prev;
		nextRect = next;
		return y + StepNavOverlay.BUTTON_H;
	}

	private void clearArrowRects()
	{
		prevRect = null;
		nextRect = null;
	}

	/**
	 * @param screen a click location in screen coordinates
	 * @return +1 when it hits the attached next-arrow, -1 for previous, 0 for neither
	 */
	int hitArrow(Point screen)
	{
		return StepNavOverlay.hitArrow(screen, getBounds(), prevRect, nextRect);
	}

	/** The box background: near-black at the configured opacity. */
	private Color backgroundColor()
	{
		int alpha = Math.max(0, Math.min(255, Math.round(255 * config.hudBackgroundOpacity() / 100f)));
		return new Color(BG_BASE.getRed(), BG_BASE.getGreen(), BG_BASE.getBlue(), alpha);
	}
}
