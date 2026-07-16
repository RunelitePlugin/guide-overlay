package com.hcimguide;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Inventory-style grid of item icons for a step's item requirements.
 * Slots get a green border when the item is currently in the inventory,
 * red when missing.
 */
public class ItemGridPanel extends JPanel
{
	/**
	 * Sized to FIT the RuneLite side panel: usable row width is roughly
	 * 200px (225px panel minus borders, scrollbar, and step indentation),
	 * so 5 columns x 32px slots + gaps + border = ~172px always fits.
	 * Icons wider than a slot are scaled down on load.
	 */
	private static final int COLUMNS = 5;
	private static final int SLOT = 32;
	private static final Color PRESENT = new Color(0, 200, 120);
	private static final Color MISSING = new Color(190, 60, 60);

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

	ItemGridPanel(List<ItemReq> items)
	{
		int rows = (items.size() + COLUMNS - 1) / COLUMNS;
		setLayout(new GridLayout(rows, COLUMNS, 1, 1));
		setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		setBorder(BorderFactory.createEmptyBorder(2, 1, 4, 1));
		// the step row wraps this panel in a left-aligned FlowLayout, which
		// respects preferred size - so the grid keeps its natural width

		for (ItemReq req : items)
		{
			JLabel label = new JLabel();
			label.setHorizontalAlignment(SwingConstants.CENTER);
			label.setVerticalAlignment(SwingConstants.CENTER);
			label.setPreferredSize(new Dimension(SLOT, SLOT));
			label.setOpaque(true);
			label.setBackground(new Color(48, 44, 38));
			label.setToolTipText(req.toString());
			label.setFont(FontManager.getRunescapeSmallFont());
			// text fallback until (or unless) an icon resolves
			label.setText(abbreviate(req.getName()));
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

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
			filler.setPreferredSize(new Dimension(SLOT, SLOT));
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
				// item sprites are 36x32; scale down so nothing clips in a
				// narrower slot (aspect ratio preserved)
				if (img.getWidth() > SLOT - 2)
				{
					slot.label.setIcon(new ImageIcon(img.getScaledInstance(
						SLOT - 2, -1, java.awt.Image.SCALE_SMOOTH)));
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

	/** Detach this grid from future async icon callbacks. */
	void dispose()
	{
		disposed = true;
	}

	private static void setSlotBorder(Slot slot, boolean present)
	{
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
