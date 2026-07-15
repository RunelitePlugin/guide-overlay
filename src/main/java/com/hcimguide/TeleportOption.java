package com.hcimguide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One teleport the route suggester can recommend: a destination plus the
 * items needed to use it. Coordinates are plain ints (not WorldPoint) so
 * this class - and everything that reasons about routes - stays pure Java
 * and unit-testable without a RuneLite client.
 */
public class TeleportOption
{
	/** Config-facing grouping; each category has its own enable toggle. */
	public enum Category
	{
		SPELL,
		TAB,
		JEWELRY,
		OTHER
	}

	/**
	 * One item requirement. {@code namePart} is a lowercase PREFIX of the
	 * in-game item name ("law rune", "ring of dueling"), so charge variants
	 * ("Ring of dueling(8)") match automatically. {@code chargedOnly}
	 * restricts matches to names carrying a "(...)" suffix - this is what
	 * separates a charged Amulet of glory from a dead one, whose name has
	 * no suffix at all.
	 */
	public static class ItemNeed
	{
		private final String namePart;
		private final int qty;
		private final boolean chargedOnly;

		ItemNeed(String namePart, int qty, boolean chargedOnly)
		{
			this.namePart = namePart;
			this.qty = qty;
			this.chargedOnly = chargedOnly;
		}

		public String getNamePart()
		{
			return namePart;
		}

		public int getQty()
		{
			return qty;
		}

		public boolean isChargedOnly()
		{
			return chargedOnly;
		}
	}

	private final String name;
	private final Category category;
	private final int x;
	private final int y;
	private final int plane;
	private final List<ItemNeed> needs;

	TeleportOption(String name, Category category, int x, int y, int plane, ItemNeed... needs)
	{
		this.name = name;
		this.category = category;
		this.x = x;
		this.y = y;
		this.plane = plane;
		this.needs = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(needs)));
	}

	public String getName()
	{
		return name;
	}

	public Category getCategory()
	{
		return category;
	}

	public int getX()
	{
		return x;
	}

	public int getY()
	{
		return y;
	}

	public int getPlane()
	{
		return plane;
	}

	/** Empty list = free (e.g. the home teleport). */
	public List<ItemNeed> getNeeds()
	{
		return needs;
	}

	// -------------------------------------------------------------- builders

	static ItemNeed rune(String runeName, int qty)
	{
		return new ItemNeed(runeName, qty, false);
	}

	/** Charged jewelry: only "(n)"-suffixed variants count. */
	static ItemNeed jewelry(String namePrefix)
	{
		return new ItemNeed(namePrefix, 1, true);
	}

	static ItemNeed item(String namePrefix)
	{
		return new ItemNeed(namePrefix, 1, false);
	}
}
