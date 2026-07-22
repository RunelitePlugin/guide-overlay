package com.hcimguide;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Outlines the chat dialogue option the guide says to click, Quest
 * Helper-style. The guide's "(2,1)" notation is parsed per step; the plugin
 * tracks which menu of the conversation is showing and this overlay draws a
 * box around that option's text. Display only - the player still clicks.
 */
public class DialogOptionOverlay extends Overlay
{
	private final Client client;
	private final HcimGuidePlugin plugin;
	private final HcimGuideConfig config;

	@Inject
	public DialogOptionOverlay(Client client, HcimGuidePlugin plugin, HcimGuideConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.highlightDialogOptions())
		{
			return null;
		}
		int option = plugin.getDialogHighlightOption();
		if (option < 1)
		{
			return null;
		}
		Widget menu = client.getWidget(InterfaceID.CHATMENU, 1);
		if (menu == null || menu.isHidden())
		{
			return null;
		}
		Widget[] children = menu.getDynamicChildren();
		// children[0] is the "Select an Option" title; options start at 1
		if (children == null || option >= children.length)
		{
			return null;
		}
		Widget target = children[option];
		if (target == null || target.isHidden())
		{
			return null;
		}
		Rectangle b = target.getBounds();
		if (b == null || b.width <= 0)
		{
			return null;
		}
		Color color = config.dialogOptionColor();
		g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
		g.fillRoundRect(b.x - 3, b.y - 2, b.width + 6, b.height + 4, 6, 6);
		g.setColor(color);
		g.drawRoundRect(b.x - 3, b.y - 2, b.width + 6, b.height + 4, 6, 6);
		return null;
	}
}
