package com.hcimguide;

/**
 * An item requirement parsed from a step, e.g. "3x Logs" -&gt; name "Logs", quantity 3.
 * Normalized forms are precomputed because comparisons happen in per-tick and
 * per-inventory-change hot paths.
 */
public class ItemReq
{
	private final String name;
	private final int quantity;
	private final String normalized;
	private final String singularized;

	public ItemReq(String name, int quantity)
	{
		this.name = name;
		this.quantity = quantity;
		this.normalized = Names.normalize(name);
		this.singularized = Names.singularize(name);
	}

	public String getName()
	{
		return name;
	}

	public int getQuantity()
	{
		return quantity;
	}

	/** Normalized form used as an exact-match key. */
	public String getNormalized()
	{
		return normalized;
	}

	/** Plural-tolerant form used as a fallback match key. */
	public String getSingularized()
	{
		return singularized;
	}

	public static String normalize(String s)
	{
		return Names.normalize(s);
	}

	/** True if the normalized names match, tolerating simple plural forms. */
	public static boolean namesEquivalent(String a, String b)
	{
		String na = Names.normalize(a);
		String nb = Names.normalize(b);
		if (na.isEmpty() || nb.isEmpty())
		{
			return false;
		}
		return na.equals(nb) || Names.singularize(a).equals(Names.singularize(b));
	}

	@Override
	public String toString()
	{
		return quantity > 1 ? quantity + "x " + name : name;
	}
}
