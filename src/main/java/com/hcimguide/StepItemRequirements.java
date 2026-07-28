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
		StepCondition condition = ConditionParser.parse(step.getText());
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
