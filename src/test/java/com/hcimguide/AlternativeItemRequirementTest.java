package com.hcimguide;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AlternativeItemRequirementTest
{
	@Test
	public void aggregatesInterchangeableInventoryItems()
	{
		ItemReq food = ItemReq.anyOf("Any cooked food", 21, "3×7",
			"Shark", "Lobster", "Swordfish");
		Map<String, Integer> exact = new HashMap<>();
		exact.put(Names.normalize("Shark"), 10);
		exact.put(Names.normalize("Lobster"), 6);
		exact.put(Names.normalize("Swordfish"), 5);
		InventorySnapshot snapshot = new InventorySnapshot(exact, new HashMap<>());

		assertEquals(21, snapshot.countOf(food));
		assertTrue(food.isAlternativeGroup());
		// an alternative GROUP shows its first option; only a CATEGORY uses the
		// declared lowest tier
		assertEquals("Shark", food.getIconName());
		assertEquals("3×7 Any cooked food (any of: Shark, Lobster, Swordfish)", food.toString());
	}

	/**
	 * "Random cooked food" is a RUNTIME CATEGORY, not a fixed alternatives
	 * list: membership is decided against live item definitions (anything
	 * edible and prepared), with the bank-tag examples only a representative
	 * subset. This pins the final category contract - an earlier design used
	 * a hardcoded anyOf list and was superseded.
	 */
	@Test
	public void randomFoodOverrideUsesTheCookedFoodCategory()
	{
		List<ItemReq> reqs = StepItemOverrides.forStep(
			"Withdraw: Bow of Faerdhinen & Crystal Armour, Seal of Passage, 3 lots of 7 random cooked food");
		ItemReq food = reqs.get(reqs.size() - 1);

		assertEquals("Any cooked food", food.getName());
		assertEquals(21, food.getQuantity());
		assertEquals("3×7", food.getQuantityLabel());
		assertTrue(food.isCategoryRequirement());
		assertEquals(ItemCategory.COOKED_FOOD, food.getCategory());
		// lowest tier represents the family, so the slot never implies gear
		// the step does not actually need
		assertEquals("Shrimps", food.getIconName());
		assertTrue(food.getMatchNames().contains("Cooked karambwan"));
	}

	@Test
	public void logOrAxeIsSatisfiedByEitherChoice()
	{
		List<ItemReq> reqs = StepItemOverrides.forStep(
			"Withdraw: Coins, Dueling Ring, a knife, log or axe, ardy cloak, dramen staff.");
		ItemReq choice = reqs.get(3);
		Map<String, Integer> exact = new HashMap<>();
		exact.put(Names.normalize("Rune axe"), 1);
		InventorySnapshot snapshot = new InventorySnapshot(exact, new HashMap<>());

		assertEquals("Log or axe", choice.getName());
		assertEquals(1, snapshot.countOf(choice));
	}
}
