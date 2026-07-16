package com.hcimguide;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Item icons for a step's requirements, drawn on an OSRS-inventory-style
 * panel: the classic stone-brown background with a beveled edge, four
 * columns wide like the real inventory. The background is PAINTED (plain
 * gradients and lines in the game's palette), not a bundled game texture,
 * so no copyrighted asset ships with the plugin. Slots get a green border
 * when the item is currently carried, red when missing.
 */
public class ItemGridPanel extends JPanel
{
	/**
	 * Four columns, like the in-game inventory. 4 x 36px slots + gaps +
	 * border = ~158px, well inside the side panel's ~200px usable row width
	 * even on indented steps. Item sprites are 36x32 natively, so they fit
	 * without scaling; anything wider is scaled down on load.
	 */
	private static final int COLUMNS = 4;
	private static final int SLOT = 36;
	private static final int SLOT_H = 32;
	private static final Color PRESENT = new Color(0, 200, 120, 170);
	private static final Color MISSING = new Color(190, 60, 60, 170);

	// fallback palette while the real sprite loads (painted, not a game asset)
	private static final Color INV_BG = new Color(62, 53, 41);
	private static final Color INV_BG_LIGHT = new Color(73, 64, 52);
	private static final Color INV_EDGE_DARK = new Color(43, 36, 27);
	private static final Color INV_EDGE_LIGHT = new Color(94, 84, 66);

	/**
	 * The ACTUAL in-game inventory stone background, pulled from the player's
	 * own game files via SpriteManager at runtime (never bundled). While it
	 * loads - or if the cache read ever fails - the painted fallback shows.
	 */
	private static volatile BufferedImage inventoryBackground;

	static void setInventoryBackground(BufferedImage img)
	{
		inventoryBackground = img;
	}

	private final List<Slot> slots = new ArrayList<>();

	/**
	 * Set when the panel generation is discarded (episode switch / re-import),
	 * so late-arriving async icon loads can't resurrect a dead component tree.
	 */
	private volatile boolean disposed;

	private static class Slot
	{
		final ItemReq req;
		final JLabel label;
		boolean present;

		Slot(ItemReq req, JLabel label)
		{
			this.req = req;
			this.label = label;
		}
	}

	/** When false, no colored have-it borders - a clean wiki-style inventory. */
	private final boolean presenceBorders;

	ItemGridPanel(List<ItemReq> items, boolean presenceBorders)
	{
		this.presenceBorders = presenceBorders;
		int rows = (items.size() + COLUMNS - 1) / COLUMNS;
		setLayout(new GridLayout(rows, COLUMNS, 2, 2));
		setOpaque(false); // background painted in paintComponent
		setBorder(BorderFactory.createEmptyBorder(4, 4, 5, 4));
		// the step row wraps this panel in a left-aligned FlowLayout, which
		// respects preferred size - so the grid keeps its natural width

		for (ItemReq req : items)
		{
			JLabel label = new JLabel();
			label.setHorizontalAlignment(SwingConstants.CENTER);
			label.setVerticalAlignment(SwingConstants.CENTER);
			label.setPreferredSize(new Dimension(SLOT, SLOT_H));
			label.setOpaque(false); // items sit directly on the inventory brown
			label.setToolTipText(req.toString());
			label.setFont(FontManager.getRunescapeSmallFont());
			// text fallback until (or unless) an icon resolves
			label.setText(abbreviate(req.getName()));
			label.setForeground(new Color(214, 195, 152)); // parchment on brown

			Slot slot = new Slot(req, label);
			slots.add(slot);
			setSlotBorder(slot, false);
			add(label);
		}

		// pad the last row so slots keep their size
		int pad = rows * COLUMNS - items.size();
		for (int i = 0; i < pad; i++)
		{
			JLabel filler = new JLabel();
			filler.setPreferredSize(new Dimension(SLOT, SLOT_H));
			add(filler);
		}
	}

	/**
	 * Resolve icons via the item manager. Call from any thread; icon updates
	 * hop to the EDT when each image loads.
	 *
	 * @param ids one resolved item id per slot, -1 for unresolved
	 */
	void applyIcons(ItemManager itemManager, int[] ids)
	{
		for (int i = 0; i < slots.size() && i < ids.length; i++)
		{
			if (ids[i] <= 0)
			{
				continue;
			}
			Slot slot = slots.get(i);
			int qty = slot.req.getQuantity();
			AsyncBufferedImage img = itemManager.getImage(ids[i], qty, qty > 1);
			Runnable apply = () ->
			{
				if (disposed)
				{
					return;
				}
				slot.label.setText(null);
				// item sprites are 36x32 natively - exactly the slot size, so
				// they render crisp and unscaled; only oversized images shrink
				if (img.getWidth() > SLOT)
				{
					slot.label.setIcon(new ImageIcon(img.getScaledInstance(
						SLOT, -1, java.awt.Image.SCALE_SMOOTH)));
				}
				else
				{
					slot.label.setIcon(new ImageIcon(img));
				}
				slot.label.revalidate();
				slot.label.repaint();
			};
			img.onLoaded(() -> SwingUtilities.invokeLater(apply));
			SwingUtilities.invokeLater(apply);
		}
	}

	/** Update presence borders from the current inventory snapshot (O(1) per slot). */
	void updatePresence(InventorySnapshot snapshot)
	{
		for (Slot slot : slots)
		{
			boolean present = snapshot.countOf(slot.req) >= slot.req.getQuantity();
			if (present != slot.present)
			{
				slot.present = present;
				setSlotBorder(slot, present);
			}
		}
	}

	/**
	 * Background: the REAL inventory stone texture from the game's own files
	 * (tiled at native pixel density, like the wiki's inventory images).
	 * Falls back to a painted approximation until the sprite loads.
	 */
	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			int w = getWidth();
			int h = getHeight();
			BufferedImage bg = inventoryBackground;
			if (bg != null && bg.getWidth() > 0 && bg.getHeight() > 0)
			{
				g2.setPaint(new TexturePaint(bg,
					new Rectangle2D.Float(0, 0, bg.getWidth(), bg.getHeight())));
				g2.fillRect(0, 0, w, h);
				g2.setColor(INV_EDGE_DARK);
				g2.drawRect(0, 0, w - 1, h - 1);
			}
			else
			{
				g2.setPaint(new GradientPaint(0, 0, INV_BG_LIGHT, 0, h, INV_BG));
				g2.fillRect(0, 0, w, h);
				g2.setColor(INV_EDGE_DARK);
				g2.drawRect(0, 0, w - 1, h - 1);
				g2.setColor(INV_EDGE_LIGHT);
				g2.drawLine(1, 1, w - 2, 1);
				g2.drawLine(1, 1, 1, h - 2);
			}
		}
		finally
		{
			g2.dispose();
		}
		super.paintComponent(g);
	}

	/** Detach this grid from future async icon callbacks. */
	void dispose()
	{
		disposed = true;
	}

	private void setSlotBorder(Slot slot, boolean present)
	{
		if (!presenceBorders)
		{
			slot.label.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
			return;
		}
		slot.label.setBorder(BorderFactory.createLineBorder(present ? PRESENT : MISSING, 1));
	}

	private static String abbreviate(String name)
	{
		String s = name.trim();
		return s.length() <= 6 ? s : s.substring(0, 5) + "…";
	}

	List<ItemReq> getItems()
	{
		List<ItemReq> out = new ArrayList<>();
		for (Slot s : slots)
		{
			out.add(s.req);
		}
		return out;
	}
}
