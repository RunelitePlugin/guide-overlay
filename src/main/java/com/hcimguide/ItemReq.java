package com.hcimguide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An item requirement parsed from a step. A requirement may name one exact
 * item or an interchangeable group such as "any cooked food".
 *
 * <p>For alternative groups, {@link #getQuantity()} is the minimum TOTAL
 * quantity across every eligible item. This lets a guide express requirements
 * such as "21 food of any cooked type" without inventing three exact foods.
 * The ordered alternatives are also used by bank tags so the player can see
 * the valid choices they actually own.</p>
 */
public class ItemReq
{
	private final String name;
	private final int quantity;
	/** Minimum held quantity used for borders/readiness/auto-completion. */
	private final int completionQuantity;
	/** True when the source wording was "inventory of X". */
	private final boolean inventoryFill;
	private final String normalized;
	private final String singularized;
	private final String quantityLabel;
	private final List<String> alternatives;
	private final ItemCategory category;

	public ItemReq(String name, int quantity)
	{
		this(name, quantity, null);
	}

	public ItemReq(String name, int quantity, String quantityLabel)
	{
		this(name, quantity, quantity, false, quantityLabel, Collections.emptyList(), null);
	}

	private ItemReq(String name, int quantity, int completionQuantity, boolean inventoryFill,
		String quantityLabel, List<String> alternatives, ItemCategory category)
	{
		this.name = name;
		this.quantity = Math.max(1, quantity);
		this.completionQuantity = Math.max(1, Math.min(this.quantity, completionQuantity));
		this.inventoryFill = inventoryFill;
		this.normalized = Names.normalize(name);
		this.singularized = Names.singularize(name);
		this.quantityLabel = quantityLabel;
		this.alternatives = alternatives;
		this.category = category;
	}

	/**
	 * Build a requirement satisfied by the combined quantity of any listed
	 * alternatives. Duplicate/blank names are removed while input order is
	 * preserved.
	 */
	public static ItemReq anyOf(String displayName, int minimumTotal, String quantityLabel, String... options)
	{
		Set<String> unique = new LinkedHashSet<>();
		if (options != null)
		{
			for (String option : options)
			{
				if (option != null && !Names.normalize(option).isEmpty())
				{
					unique.add(option.trim());
				}
			}
		}
		if (unique.isEmpty())
		{
			throw new IllegalArgumentException("Alternative requirement needs at least one option");
		}
		return new ItemReq(displayName, minimumTotal, minimumTotal, false, quantityLabel,
			Collections.unmodifiableList(new ArrayList<>(unique)), null);
	}

	/** Build a requirement satisfied by any live item in a runtime category. */
	public static ItemReq category(ItemCategory category, int minimumTotal, String quantityLabel)
	{
		if (category == null)
		{
			throw new IllegalArgumentException("Category is required");
		}
		return new ItemReq(category.getDisplayName(), minimumTotal, minimumTotal, false, quantityLabel,
			Collections.emptyList(), category);
	}

	public String getName()
	{
		return name;
	}

	public int getQuantity()
	{
		return quantity;
	}

	/** Quantity that actually satisfies this requirement. */
	public int getCompletionQuantity()
	{
		return completionQuantity;
	}

	public boolean isInventoryFill()
	{
		return inventoryFill;
	}

	/** Preserve display quantity while changing the held-item threshold. */
	ItemReq withCompletionQuantity(int minimumHeld)
	{
		return new ItemReq(name, quantity, minimumHeld, inventoryFill, quantityLabel, alternatives, category);
	}

	/** Mark a parser result as originating from "inventory of X" wording. */
	ItemReq asInventoryFill()
	{
		return new ItemReq(name, quantity, completionQuantity, true, quantityLabel, alternatives, category);
	}

	public String getQuantityLabel()
	{
		return quantityLabel;
	}

	public boolean hasQuantityLabel()
	{
		return quantityLabel != null;
	}

	public String getNormalized()
	{
		return normalized;
	}

	public String getSingularized()
	{
		return singularized;
	}

	/** True when any eligible option may satisfy this requirement. */
	public boolean isAlternativeGroup()
	{
		return !alternatives.isEmpty();
	}

	public boolean isCategoryRequirement()
	{
		return category != null;
	}

	public ItemCategory getCategory()
	{
		return category;
	}

	/** Ordered eligible item names. Empty for an exact requirement. */
	public List<String> getAlternatives()
	{
		return alternatives;
	}

	/** Name used for the single representative icon in compact displays. */
	public String getIconName()
	{
		if (isCategoryRequirement())
		{
			// lowest tier stands in for the family: the least capable item that
			// satisfies the requirement, so the picture never implies gear the
			// step does not actually need
			return category.getIconName();
		}
		return isCategoryRequirement() ? category.getBankTagExamples()[0]
			: isAlternativeGroup() ? alternatives.get(0) : name;
	}

	/**
	 * Every name that can satisfy this requirement. Used for inventory and
	 * bank-tag matching.
	 *
	 * <p>Aliases used to affect only the icon, so a step asking for "Ardy Cloak"
	 * showed the right picture while still reporting the item missing. The
	 * canonical name is now a match candidate too. Callers must dedupe by
	 * resolved item, since several candidates can point at one held stack.</p>
	 */
	public List<String> getMatchNames()
	{
		if (isCategoryRequirement())
		{
			return java.util.Arrays.asList(category.getBankTagExamples());
		}
		List<String> base = isAlternativeGroup()
			? alternatives : Collections.singletonList(name);
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(base);
		for (String option : base)
		{
			String canonical = ItemAliases.canonical(Names.normalize(option));
			if (canonical != null)
			{
				out.add(canonical);
			}
		}
		return new java.util.ArrayList<>(out);
	}

	public static String normalize(String s)
	{
		return Names.normalize(s);
	}

	public static boolean namesEquivalent(String a, String b)
	{
		String na = Names.normalize(a);
		String nb = Names.normalize(b);
		if (na.isEmpty() || nb.isEmpty())
		{
			return false;
		}
		return na.equals(nb) || Names.singularMatch(a, b);
	}

	@Override
	public String toString()
	{
		String prefix = quantityLabel != null ? quantityLabel
			: quantity > 1 ? quantity + "x" : "";
		String base = prefix.isEmpty() ? name : prefix + " " + name;
		if (!isAlternativeGroup() || isCategoryRequirement())
		{
			return base;
		}
		return base + " (any of: " + String.join(", ", alternatives) + ")";
	}
}
