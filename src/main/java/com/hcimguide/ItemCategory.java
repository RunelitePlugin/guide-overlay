package com.hcimguide;

import java.util.Locale;

/** Runtime item categories evaluated from RuneLite's live item definitions. */
public enum ItemCategory
{
	/** Any edible, prepared food. Raw, uncooked and burnt items are excluded. */
	COOKED_FOOD("Any cooked food", "Shrimps", new String[]
	{
		"Shark", "Cooked karambwan", "Manta ray", "Sea turtle", "Anglerfish",
		"Dark crab", "Monkfish", "Swordfish", "Lobster", "Tuna", "Salmon", "Trout",
		"Shrimps"
	}),

	/** Any pickaxe, bronze through dragon and beyond. */
	PICKAXE("Any pickaxe", "Bronze pickaxe", new String[]
	{
		"Bronze pickaxe", "Iron pickaxe", "Steel pickaxe", "Black pickaxe",
		"Mithril pickaxe", "Adamant pickaxe", "Rune pickaxe", "Dragon pickaxe",
		"Crystal pickaxe", "Infernal pickaxe"
	}),

	/** Any woodcutting axe. */
	AXE("Any axe", "Bronze axe", new String[]
	{
		"Bronze axe", "Iron axe", "Steel axe", "Black axe", "Mithril axe",
		"Adamant axe", "Rune axe", "Dragon axe", "Crystal axe", "Infernal axe"
	}),

	/** Any worn boots. */
	BOOTS("Any boots", "Leather boots", new String[]
	{
		"Leather boots", "Boots of lightness", "Climbing boots", "Rune boots",
		"Dragon boots", "Graceful boots"
	}),

	/** Any ring. */
	RING("Any ring", "Gold ring", new String[]
	{
		"Gold ring", "Sapphire ring", "Emerald ring", "Ruby ring",
		"Diamond ring", "Ring of recoil", "Ring of dueling(8)"
	}),

	/** Any grimy herb. */
	GRIMY_HERB("Any grimy herb", "Grimy guam leaf", new String[]
	{
		"Grimy guam leaf", "Grimy marrentill", "Grimy tarromin", "Grimy harralander",
		"Grimy ranarr weed", "Grimy irit leaf", "Grimy avantoe", "Grimy kwuarm",
		"Grimy cadantine", "Grimy lantadyme", "Grimy dwarf weed", "Grimy torstol"
	});

	private final String displayName;
	/**
	 * Item shown for the slot. The LOWEST tier is used deliberately: it is the
	 * least capable thing that satisfies the requirement, so the picture never
	 * implies gear the step does not actually need.
	 */
	private final String iconName;
	private final String[] bankTagExamples;

	ItemCategory(String displayName, String iconName, String[] bankTagExamples)
	{
		this.displayName = displayName;
		this.iconName = iconName;
		this.bankTagExamples = bankTagExamples;
	}

	/** Lowest-tier member, used for the slot icon. */
	public String getIconName()
	{
		return iconName;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	/** Representative, useful choices for bank-tag expansion; not the membership definition. */
	public String[] getBankTagExamples()
	{
		return bankTagExamples.clone();
	}

	/**
	 * Membership test taken on the item's live name and inventory actions.
	 *
	 * <p>Deliberately takes plain values rather than a RuneLite
	 * {@code ItemComposition}: {@link ItemReq} holds a category, and the offline
	 * test harness compiles {@code ItemReq} without the RuneLite API. Coupling
	 * this enum to the client library made the entire harness uncompilable.
	 * The single caller passes {@code comp.getName()} and
	 * {@code comp.getInventoryActions()}.</p>
	 */
	public boolean matches(String itemName, String[] inventoryActions)
	{
		if (itemName == null)
		{
			return false;
		}
		String name = itemName.toLowerCase(Locale.ROOT);
		if (name.equals("null"))
		{
			return false;
		}
		switch (this)
		{
			case PICKAXE:
				return name.endsWith(" pickaxe") || name.equals("pickaxe");
			case AXE:
				// " axe" only, so "pickaxe" and "battleaxe" do not qualify
				return name.endsWith(" axe") || name.equals("axe");
			case BOOTS:
				return name.endsWith(" boots") || name.equals("boots");
			case RING:
				// excludes "ring of charos" style quest rings? no: any ring counts,
				// but not items that merely contain the word, like "ring mould"
				return name.endsWith(" ring") || name.startsWith("ring of ")
					|| name.equals("ring");
			case GRIMY_HERB:
				return name.startsWith("grimy ");
			case COOKED_FOOD:
			default:
				if (name.startsWith("raw ") || name.startsWith("burnt ")
					|| name.startsWith("uncooked ") || name.contains(" burnt"))
				{
					return false;
				}
				if (inventoryActions == null)
				{
					return false;
				}
				for (String action : inventoryActions)
				{
					if (action != null && action.equalsIgnoreCase("Eat"))
					{
						return true;
					}
				}
				return false;
		}
	}
}
