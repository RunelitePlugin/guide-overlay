package com.hcimguide;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ItemCategoryRequirementTest
{
	@Test
	public void categoryInventoryCountUsesRuntimeBucket()
	{
		Map<ItemCategory, Integer> categories = new EnumMap<>(ItemCategory.class);
		categories.put(ItemCategory.COOKED_FOOD, 21);
		InventorySnapshot snapshot = new InventorySnapshot(Collections.emptyMap(), Collections.emptyMap(), categories);
		ItemReq req = ItemReq.category(ItemCategory.COOKED_FOOD, 21, "3×7");
		assertEquals(21, snapshot.countOf(req));
		assertEquals("3×7 Any cooked food", req.toString());
	}

	@Test
	public void categoryIconIsTheLowestTierAndExamplesDriveBankTags()
	{
		ItemReq req = ItemReq.category(ItemCategory.COOKED_FOOD, 1, "few");
		assertTrue(req.isCategoryRequirement());
		// the icon comes from the category's declared lowest tier, NOT from the
		// first bank-tag example
		assertEquals("Shrimps", req.getIconName());
		assertTrue(req.getMatchNames().contains("Cooked karambwan"));
	}
}
