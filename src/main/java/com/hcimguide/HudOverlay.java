package com.hcimguide;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
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
	/** Stack-count yellow, matching the client's own quantity text. */
	private static final Color QTY_LABEL = new Color(255, 255, 0);
	private static final Color PRESENT = new Color(0, 200, 120, 200);
	private static final Color MISSING = new Color(190, 60, 60, 200);

	private static final int ICON_W = 36;
	private static final int ICON_H = 32;
	private static final int ICON_PAD = 2;
	/**
	 * Safety bound on how tall the item strip can grow. Deliberately generous:
	 * at two rows, narrowing the overlay dropped most of a step's items with no
	 * indication they existed.
	 */
	private static final int MAX_ICON_ROWS = 8;
	private static final int STRIP_GAP = 2;
	/** Vertical gap between the current-step box and the "Next steps" box. */
	private static final int BOX_GAP = 4;

	private final HcimGuidePlugin plugin;
	private final HcimGuideConfig config;
	private final ItemManager itemManager;
	private final net.runelite.client.game.SpriteManager spriteManager;

	/** Muted: the counter must inform, not distract from the step text. */
	private static final Color WAYPOINT_COUNTER = new Color(150, 150, 150);
	/**
	 * Sprite-backed slots ("Combat Gear") cached by sprite id. getSpriteAsync
	 * fills this in on a later frame; until then the slot simply draws nothing
	 * rather than blocking the render pass.
	 */
	private final Map<Integer, BufferedImage> spriteCache = new java.util.concurrent.ConcurrentHashMap<>();
	/** Invalidates late async sprite callbacks after shutdown. */
	private final java.util.concurrent.atomic.AtomicInteger spriteGeneration =
		new java.util.concurrent.atomic.AtomicInteger();
	/** Second box for upcoming steps, so the current step stands alone. */
	private final PanelComponent nextStepsPanel = new PanelComponent();

	/** One atomic per-frame hit snapshot; null = controls not shown this frame. */
	private volatile StepNavOverlay.HitRegions hitRegions;

	@Inject
	public HudOverlay(HcimGuidePlugin plugin, HcimGuideConfig config, ItemManager itemManager,
		net.runelite.client.game.SpriteManager spriteManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
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

		OverlayFonts.apply(graphics, config);
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
				Color stepColor = semanticStepColor(step,
					shown == 0 ? NEXT_STEP_COLOR : LATER_STEP_COLOR);
				panelComponent.getChildren().add(LineComponent.builder()
					.left((shown == 0 ? "> " : "- ") + step.getText())
					.leftColor(stepColor)
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
		// A suggested route next to an explicit "-> Fairy ring CKS" gives two
		// competing travel instructions for one step. The step already says how
		// to get there, so the suggestion is noise.
		if (route != null && config.routeSuggestions() && !plugin.isActiveStopTransport())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("★ " + route)
				.leftColor(ROUTE_COLOR)
				.build());
		}

		// The old location line ("@ place · source · confidence" plus
		// "Phase x/y · Waypoint x/y") was clutter: multiple locations and
		// confidence labels distracted from the step text. All that remains
		// is a quiet "Location  x/y" counter, so a multi-stop route still
		// shows the location will update as you travel. Deliberately NOT
		// gated by showLocationConfidence - that setting now scopes the
		// side panel's diagnostics only.
		String counter = plugin.getWaypointCounterSnapshot();
		if (counter != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Location")
				.leftColor(WAYPOINT_COUNTER)
				.right(counter)
				.rightColor(WAYPOINT_COUNTER)
				.build());
		}

		// The attached control row is drawn ABOVE the panel. Reserve its height
		// and shift the panel down, so the row sits at a fixed screen position
		// instead of moving whenever the step text changes length.
		boolean arrowsOnTop = config.navArrows() == HcimGuideConfig.ArrowMode.ATTACHED;
		int topOffset = arrowsOnTop ? StepNavOverlay.BUTTON_H + STRIP_GAP : 0;
		if (topOffset > 0)
		{
			graphics.translate(0, topOffset);
		}
		Dimension d = super.render(graphics);
		if (topOffset > 0)
		{
			graphics.translate(0, -topOffset);
		}
		if (d == null)
		{
			clearArrowRects();
			return null;
		}

		int y = topOffset + d.height;
		if (config.hudShowStepItems())
		{
			y = drawItemStrip(graphics, d.width, y);
		}
		if (upcoming != null && !upcoming.isEmpty())
		{
			y = drawNextStepsBox(graphics, d.width, y, upcoming);
		}
		if (arrowsOnTop)
		{
			// drawn at the very top, above the text, so the row keeps a fixed
			// screen position while the box below changes height between steps
			drawAttachedArrows(graphics, d.width, 0);
		}
		else if (config.navArrows() == HcimGuideConfig.ArrowMode.HIDDEN
			&& plugin.hasLocationForGuidedStep())
		{
			y = drawLocationOnly(graphics, d.width, y);
		}
		else
		{
			clearArrowRects();
		}
		return new Dimension(d.width, y);
	}

	private Color semanticStepColor(GuideStep step, Color fallback)
	{
		StepTextSemantic.Kind kind = StepTextSemantic.classify(step == null ? null : step.getText());
		switch (kind)
		{
			case DANGER:
				return config.colorDangerSteps() ? config.dangerStepColor() : fallback;
			case PREPARATION:
				return config.colorPreparationSteps() ? config.preparationStepColor() : fallback;
			case TRANSPORT:
				return config.colorTransportSteps() ? config.transportStepColor() : fallback;
			default:
				return fallback;
		}
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
			Color stepColor = semanticStepColor(step, LATER_STEP_COLOR);
			nextStepsPanel.getChildren().add(LineComponent.builder()
				.left("- " + step.getText())
				.leftColor(stepColor)
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
			if (id > 0 || ItemIconResolver.isSpriteId(id))
			{
				resolved++;
			}
		}
		if (resolved == 0)
		{
			return y;
		}

		int perRow = Math.max(1, (width - ICON_PAD) / (ICON_W + ICON_PAD));
		// Wrap onto as many rows as the items need. The row count used to be
		// capped, so narrowing the overlay reduced how many icons fit per row
		// and silently dropped everything past the cap - items appeared to
		// vanish rather than reflow. The cap now only bounds the strip when a
		// step has a genuinely huge list, and the overflow is labelled.
		int rows = (resolved + perRow - 1) / perRow;
		int hiddenCount = 0;
		if (rows > MAX_ICON_ROWS)
		{
			rows = MAX_ICON_ROWS;
			hiddenCount = resolved - perRow * MAX_ICON_ROWS;
		}
		int drawn = Math.min(resolved, perRow * rows);
		int stripH = rows * (ICON_H + ICON_PAD) + ICON_PAD;

		y += STRIP_GAP;
		g.setColor(backgroundColor());
		g.fillRect(0, y, width, stripH);

		boolean borders = config.itemPresenceBorders();
		InventorySnapshot inv = borders ? plugin.getInventorySnapshot() : null;
		int cell = 0;
		for (int i = 0; i < ids.length && cell < drawn; i++)
		{
			if (ids[i] <= 0 && !ItemIconResolver.isSpriteId(ids[i]))
			{
				continue;
			}
			int cx = ICON_PAD + (cell % perRow) * (ICON_W + ICON_PAD);
			int cy = y + ICON_PAD + (cell / perRow) * (ICON_H + ICON_PAD);
			ItemReq req = i < items.size() ? items.get(i) : null;
			int qty = req != null ? req.getQuantity() : 1;
			// an open quantity ("all", "100+") draws its own label below, so the
			// sprite is rendered without the baked-in stack number
			boolean openQty = req != null && req.hasQuantityLabel();
			BufferedImage img;
			if (ItemIconResolver.isSpriteId(ids[i]))
			{
				img = spriteFor(ItemIconResolver.spriteIdOf(ids[i]));
			}
			else
			{
				img = itemManager.getImage(ids[i], qty, !openQty && qty > 1);
			}
			if (img != null)
			{
				g.drawImage(img, cx, cy, null);
			}
			if (openQty)
			{
				String label = req.getQuantityLabel();
				java.awt.Font prev = g.getFont();
				g.setFont(prev.deriveFont(prev.getSize2D() - 1f));
				g.setColor(java.awt.Color.BLACK);
				g.drawString(label, cx + 2, cy + 10);
				g.setColor(QTY_LABEL);
				g.drawString(label, cx + 1, cy + 9);
				g.setFont(prev);
			}
			// concept slots name a concept, not an item: a presence check would
			// always fail and paint a permanent red border
			if (borders && req != null && inv != null && !ConceptItems.isConcept(req.getName()))
			{
				g.setColor(inv.countOf(req) >= req.getCompletionQuantity() ? PRESENT : MISSING);
				g.drawRect(cx, cy, ICON_W - 1, ICON_H - 1);
			}
			cell++;
		}
		if (hiddenCount > 0)
		{
			// never drop items without saying so
			g.setColor(MISSING);
			g.setFont(g.getFont().deriveFont(10f));
			g.drawString("+" + hiddenCount + " more",
				ICON_PAD, y + stripH - 2);
		}
		return y + stripH;
	}

	/** Attached step and location controls, centered under the box. */
	private int drawAttachedArrows(Graphics2D g, int width, int y)
	{
		// no leading gap: this row is drawn at the top of the overlay, where
		// there is nothing above it to separate from

		boolean showLocation = plugin.hasLocationForGuidedStep();

		// fixed three-slot row: unavailable controls are greyed rather than
		// omitted, so the arrows stay under the pointer across steps
		int totalW = StepNavOverlay.totalWidth(true);
		int x = Math.max(0, (width - totalW) / 2);
		Rectangle prev = new Rectangle(x, y, StepNavOverlay.BUTTON_W, StepNavOverlay.BUTTON_H);
		x += StepNavOverlay.BUTTON_W + StepNavOverlay.BUTTON_GAP;
		Rectangle location = new Rectangle(x, y, StepNavOverlay.BUTTON_W, StepNavOverlay.BUTTON_H);
		x += StepNavOverlay.BUTTON_W + StepNavOverlay.BUTTON_GAP;
		Rectangle next = new Rectangle(x, y, StepNavOverlay.BUTTON_W, StepNavOverlay.BUTTON_H);

		StepNavOverlay.drawArrowButton(g, prev, false);
		StepNavOverlay.drawLocationButton(g, location,
			plugin.isLocationGuideHiddenForGuidedStep(), showLocation);
		StepNavOverlay.drawArrowButton(g, next, true);
		hitRegions = new StepNavOverlay.HitRegions(prev, location, next);
		return y + StepNavOverlay.BUTTON_H;
	}

	/** The location control remains available when step arrows are disabled. */
	private int drawLocationOnly(Graphics2D g, int width, int y)
	{
		y += STRIP_GAP;
		int x = Math.max(0, (width - StepNavOverlay.BUTTON_W) / 2);
		Rectangle location = new Rectangle(x, y, StepNavOverlay.BUTTON_W, StepNavOverlay.BUTTON_H);
		StepNavOverlay.drawLocationButton(g, location, plugin.isLocationGuideHiddenForGuidedStep());
		hitRegions = new StepNavOverlay.HitRegions(null, location, null);
		return y + StepNavOverlay.BUTTON_H;
	}


	/**
	 * Client sprite for a sprite-backed slot, or null while it loads. Requested
	 * once per sprite id; the async callback populates the cache for the next
	 * frame, so render is never blocked on a file read.
	 */
	/** Sprite ids whose async load is already in flight - request each once. */
	private final java.util.Set<Integer> spriteRequested =
		java.util.concurrent.ConcurrentHashMap.newKeySet();

	private BufferedImage spriteFor(int spriteId)
	{
		BufferedImage cached = spriteCache.get(spriteId);
		if (cached != null)
		{
			return cached;
		}
		if (spriteRequested.add(spriteId))
		{
			final int generation = spriteGeneration.get();
			spriteManager.getSpriteAsync(spriteId, 0, img ->
			{
				if (spriteGeneration.get() != generation)
				{
					return;
				}
				if (img != null)
				{
					spriteCache.put(spriteId, img);
				}
				else
				{
					// load failed - allow a later frame to retry
					spriteRequested.remove(spriteId);
				}
			});
		}
		return null;
	}

	/** Release cached sprite images and invalidate callbacks when the plugin stops. */
	void resetCaches()
	{
		spriteGeneration.incrementAndGet();
		spriteCache.clear();
		spriteRequested.clear();
		nextStepsPanel.getChildren().clear();
		clearArrowRects();
	}

	private void clearArrowRects()
	{
		hitRegions = null;
	}

	int hitArrow(Point screen)
	{
		StepNavOverlay.HitRegions r = hitRegions;
		return r == null ? 0 : StepNavOverlay.hitArrow(screen, getBounds(), r.prev, r.next);
	}

	boolean hitLocationToggle(Point screen)
	{
		StepNavOverlay.HitRegions r = hitRegions;
		return r != null && StepNavOverlay.hitButton(screen, getBounds(), r.location);
	}

	/** The box background: near-black at the configured opacity. */
	private Color backgroundColor()
	{
		int alpha = Math.max(0, Math.min(255, Math.round(255 * config.hudBackgroundOpacity() / 100f)));
		return new Color(BG_BASE.getRed(), BG_BASE.getGreen(), BG_BASE.getBlue(), alpha);
	}
}
