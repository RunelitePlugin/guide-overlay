package com.hcimguide;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Small movable on-screen panel showing the active bank and its next few
 * unchecked steps, so the sidebar doesn't need to stay open while playing.
 *
 * Renders on the client thread; reads only the plugin's cached active bank
 * (O(1)) and iterates its ~30 steps, so per-frame cost is negligible.
 */
public class HudOverlay extends OverlayPanel
{
	private static final int MAX_LINE_CHARS = 38;
	private static final Color NEXT_STEP_COLOR = Color.WHITE;
	private static final Color LATER_STEP_COLOR = new Color(180, 180, 180);

	private final HcimGuidePlugin plugin;
	private final HcimGuideConfig config;

	@Inject
	public HudOverlay(HcimGuidePlugin plugin, HcimGuideConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showHudOverlay())
		{
			return null;
		}
		GuideBank bank = plugin.getActiveBank();
		if (bank == null)
		{
			return null;
		}

		OverlayFonts.apply(graphics, config.overlayFontStyle());
		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(config.hudWidth(), 0));

		int total = bank.getSteps().size();
		int done = plugin.countCompleted(bank.getSteps());
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(bank.getTitle() + "  (" + done + "/" + total + ")")
			.build());

		int shown = 0;
		int max = config.hudMaxSteps();
		for (GuideStep step : bank.getSteps())
		{
			if (plugin.isCompleted(step.getKey()))
			{
				continue;
			}
			panelComponent.getChildren().add(LineComponent.builder()
				.left((shown == 0 ? "> " : "- ") + truncate(step.getText()))
				.leftColor(shown == 0 ? NEXT_STEP_COLOR : LATER_STEP_COLOR)
				.build());
			if (++shown >= max)
			{
				break;
			}
		}

		return super.render(graphics);
	}

	private static String truncate(String s)
	{
		return s.length() <= MAX_LINE_CHARS ? s : s.substring(0, MAX_LINE_CHARS - 1) + "…";
	}
}
