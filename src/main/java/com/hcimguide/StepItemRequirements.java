package com.hcimguide;

import java.util.List;

/**
 * One authoritative projection of the item requirements shown for a guide step.
 *
 * <p>Condition-backed withdraw/gather steps use their parsed condition items.
 * Display-only JSON guide suffixes use the explicit {@code (Items: ...)} list.
 * Keeping this rule in one place prevents the panel, preload, and audit paths
 * from silently inspecting different requirements.</p>
 */
final class StepItemRequirements
{
	private StepItemRequirements()
	{
	}

	static List<ItemReq> displayFor(GuideStep step)
	{
		if (step == null)
		{
			return null;
		}
		return displayFor(step, ConditionParser.parse(step.getText()));
	}

	/**
	 * Same projection for a caller that already holds the step's condition.
	 * Prefer this over the text-only form: a JSON guide step carries its
	 * complete item list separately, while its text shows at most the first
	 * 120 characters of it.
	 */
	static List<ItemReq> displayFor(GuideStep step, StepCondition condition)
	{
		if (step == null)
		{
			return null;
		}
		if (step.getItemsNeeded() != null && (condition == null
			|| condition.getType() != StepCondition.Type.ITEMS_IN_INVENTORY))
		{
			List<ItemReq> full = ItemListParser.parseItemsNeeded(step.getItemsNeeded());
			if (full != null)
			{
				return full;
			}
		}
		return displayFor(step.getText(), condition);
	}

	static List<ItemReq> displayFor(String text, StepCondition condition)
	{
		if (condition != null
			&& condition.getType() == StepCondition.Type.ITEMS_IN_INVENTORY)
		{
			return condition.getItems();
		}
		return ItemListParser.parseItemsSuffix(text);
	}
}
