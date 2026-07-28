package com.hcimguide;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the parsing and requirement behaviour added in
 * 1.5.4 and 1.5.5. Each test names the bug it protects against.
 */
public class NewBehaviourTest
{
	private static String render(String step)
	{
		List<ItemReq> reqs = ItemListParser.parse(step);
		if (reqs == null)
		{
			return "(null)";
		}
		StringBuilder sb = new StringBuilder();
		for (ItemReq r : reqs)
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(r.toString());
		}
		return sb.toString();
	}

	@Test
	public void instructionAfterAndIsNotAnItem()
	{
		assertEquals("Rope", render("Withdraw: Rope and climb down"));
	}

	@Test
	public void quantityLabelsRenderWithoutAnExtraX()
	{
		assertEquals("100+ Cosmic Runes", render("Buy 100+ Cosmic Runes"));
		// "Food" is now the cooked-food category, so the display name changed.
		// The point of this check is that the vague quantity label survives.
		assertEquals("few Any cooked food", render("Withdraw: Few food"));
	}

	/** Spelled-out counts used to parse as 1, so steps completed after one item. */
	@Test
	public void spelledOutQuantitiesAreCounted()
	{
		assertEquals("2x cheese", render("Buy two cheese"));
		assertEquals("3x raw beef", render("Buy three raw beef"));
		assertEquals("8x redberries", render("Buy eight redberries"));
	}

	/** "and" separates list members but is part of some real item names. */
	@Test
	public void andSplitsProseButNotItemNames()
	{
		assertEquals("2x cheese, 3x raw beef", render("Buy two cheese and three raw beef"));
		assertEquals("Pestle and mortar, Vial of water",
			render("Withdraw: Pestle and mortar, Vial of water"));
		assertEquals("Bow and arrow, Coins", render("Withdraw: Bow and arrow, Coins"));
	}

	@Test
	public void thousandsSuffixExpands()
	{
		assertEquals("16000x feathers", render("Buy 16k feathers"));
	}

	/**
	 * "100+ Cosmic Runes" lost its item when it was not the first entry: the
	 * boundary split cut at the '+' before the segment's own count was read.
	 */
	@Test
	public void openQuantitySurvivesInAnyListPosition()
	{
		assertEquals("100+ Cosmic Runes", render("Buy 100+ Cosmic Runes"));
		assertEquals("500x Law Runes, 100+ Cosmic Runes",
			render("Buy 500 Law Runes, 100+ Cosmic Runes"));
	}

	@Test
	public void hedgesBeforeACountAreStripped()
	{
		assertEquals("4x Jute seeds", render("Buy at least 4x Jute seeds"));
		assertEquals("100x Soda Ash", render("Buy your 100 Soda Ash"));
	}

	/** A shared trailing noun distributes, but only over bare modifiers. */
	@Test
	public void sharedTrailingNounDistributes()
	{
		assertEquals("Bronze Knives, Iron Knives, Steel Knives",
			render("Buy Bronze, Iron, and Steel Knives"));
	}

	@Test
	public void sharedTrailingNounDoesNotTouchOrdinaryLists()
	{
		assertEquals("Coins, Spade, Dramen Staff",
			render("Withdraw: Coins, Spade, Dramen Staff"));
	}

	/** Dose plurals resolve through the alias layer, not a per-step override. */
	@Test
	public void dosePluralsResolve()
	{
		assertEquals("56x Waterskins(4)", render("Buy 2x Inventory of Waterskins(4)"));
		assertEquals("Waterskin(4)", ItemAliases.canonical(ItemReq.normalize("Waterskins(4)")));
	}

	@Test
	public void namedTeleportExpandsToItsRunes()
	{
		assertEquals("2x Fire rune, 2x Law rune",
			render("Withdraw: trollheim teleport runes"));
	}

	/** A vague count keeps the guide's own wording as the slot label. */
	@Test
	public void vagueQuantityKeepsTheGuideWording()
	{
		List<ItemReq> reqs = ItemListParser.parse("Withdraw: Few food");
		assertEquals("few", reqs.get(0).getQuantityLabel());
		// bare "food" stays free text on purpose: any cooked food satisfies it,
		// and a specific alias would show a missing border to a player carrying
		// a different food
		assertNull(ItemAliases.canonical(ItemReq.normalize("food")));
	}

	/**
	 * Concept phrases name no item, so they must be recognised as concepts:
	 * they render a sprite, skip inventory checks, and never block trip ready.
	 */
	@Test
	public void conceptPhrasesAreRecognised()
	{
		assertTrue(ConceptItems.isConcept("Combat Gear"));
		assertTrue(ConceptItems.isConcept("Range Gear"));
		assertFalse(ConceptItems.isConcept("combat stats"));
		assertFalse(ConceptItems.isConcept("Shark"));
	}

	/**
	 * An override replaces the whole parsed list for its step, so it must list
	 * everything the step needs. These three previously dropped items.
	 */
	@Test
	public void overridesKeepEveryItemTheStepNeeds()
	{
		String gauntlet = render("Withdraw: Bow of Faerdhinen & Crystal Armour, "
			+ "Seal of Passage, 3 lots of 7 random cooked food");
		assertTrue(gauntlet.contains("Bow of Faerdhinen"));
		assertTrue(gauntlet.contains("Seal of Passage"));
		// the food requirement renders as its category display name; the
		// representative Shark icon is a display concern (getIconName)
		assertTrue(gauntlet.contains("Any cooked food"));

		String seeds = render("Buy at least 4x Jute seeds, 1x Marigold seeds, "
			+ "6x Onion seeds, 6x Cabbage seeds for "
			+ "[Kandarin Easy Diary and Garden of Tranquility]");
		assertTrue(seeds.contains("Jute seed"));
		assertTrue(seeds.contains("Marigold seed"));
		assertTrue(seeds.contains("Onion seed"));
		assertTrue(seeds.contains("Cabbage seed"));

		String withdraw = render("Withdraw: Coins, Dueling Ring, a knife, "
			+ "log or axe, ardy cloak, dramen staff.");
		assertTrue(withdraw.contains("Coins"));
		assertTrue(withdraw.contains("Knife"));
		assertTrue(withdraw.contains("Dramen staff"));
	}
}
