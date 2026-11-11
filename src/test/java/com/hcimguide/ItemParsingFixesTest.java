package com.hcimguide;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Item-list wordings taken from the two bundled guides that used to parse wrongly. */
public class ItemParsingFixesTest
{
	private static List<String> names(List<ItemReq> items)
	{
		List<String> out = new ArrayList<>();
		for (ItemReq req : items)
		{
			out.add(req.getName());
		}
		return out;
	}

	private static ItemReq find(List<ItemReq> items, String name)
	{
		for (ItemReq req : items)
		{
			if (req.getName().equalsIgnoreCase(name))
			{
				return req;
			}
		}
		throw new AssertionError(name + " not in " + items);
	}

	@Test
	public void itemsNamedAfterAVerbAreItems()
	{
		List<ItemReq> withdraw = ItemListParser.parse("Withdraw: Teleport Crystal, Ardy Cloak, Dramen Staff");
		assertEquals(3, withdraw.size());
		assertTrue(withdraw.get(0).isAlternativeGroup());
		assertTrue(withdraw.get(0).getAlternatives().contains("Teleport crystal (1)"));

		List<ItemReq> buy = ItemListParser.parse("Buy Chronicle & 10 Teleport cards from Diango");
		assertEquals(10, find(buy, "Teleport cards").getQuantity());
		assertEquals("Teleport card", ItemAliases.canonical(Names.normalize("Teleport cards")));
		assertEquals(3, find(ItemListParser.parse(
			"Buy a Chronicle, three teleport cards and a rainbow scarf from Diango."), "teleport cards").getQuantity());

		// real instructions after a separator are still dropped
		assertEquals(1, ItemListParser.parse("Withdraw: Coins & teleport out").size());
		assertEquals(1, ItemListParser.parse("Buy Desert Robes & wear them").size());
	}

	@Test
	public void bareWaterIsARuneOnlyInRuneContext()
	{
		List<ItemReq> counted = ItemListParser.parse("Withdraw: Coins, Willow Blackjack, 2 Law, 2 Water, 23x Wines");
		assertEquals(2, find(counted, "Water rune").getQuantity());

		List<ItemReq> shared = ItemListParser.parse("Withdraw: Astral Runes, Water & earth Runes, Waterskin");
		assertEquals(1, find(shared, "Water rune").getQuantity());

		// with neither a count nor a rune neighbour it stays prose
		assertFalse(names(ItemListParser.parse("Withdraw: Bucket, Water, Rope")).contains("Water rune"));
		assertNull(ItemListParser.parse("Collect water"));
	}

	@Test
	public void aCountWrittenOnceCoversTheWholeSlashGroup()
	{
		List<ItemReq> dyes = ItemListParser.parse("Withdraw: Range Gear + 2x Red/Yellow/Blue Dyes, Coins");
		assertEquals(2, find(dyes, "Red Dyes").getQuantity());
		assertEquals(2, find(dyes, "Yellow Dyes").getQuantity());
		assertEquals(2, find(dyes, "Blue Dyes").getQuantity());

		List<ItemReq> raw = ItemListParser.parse("Withdraw: 3x Raw Rat/Chicken");
		assertEquals(3, find(raw, "Raw Rat").getQuantity());
		assertEquals(3, find(raw, "Raw Chicken").getQuantity());

		// each part's own count is kept where it is written
		List<ItemReq> runes = ItemListParser.parse("Withdraw: Mind/6xAir/6xEarth/All Water Runes");
		assertEquals(1, find(runes, "Mind Runes").getQuantity());
		assertEquals(6, find(runes, "Air Runes").getQuantity());
		assertEquals("all", find(runes, "Water Runes").getQuantityLabel());
	}

	@Test
	public void eitherDoseIsOneAnyDoseRequirement()
	{
		List<ItemReq> items = ItemListParser.parse("Withdraw: Axe, Defence Potion (3)/(4), Volcanic Sulphur");
		assertEquals(3, items.size());
		assertTrue(items.get(1).isAlternativeGroup());
		assertTrue(items.get(1).getAlternatives().contains("Defence potion(3)"));
	}

	@Test
	public void boltsNextToABoneCrossbowAreBoneBolts()
	{
		assertTrue(names(ItemListParser.parse("Withdraw: Bone Crossbow + Bolts, Coins")).contains("Bone bolts"));
		assertTrue(names(ItemListParser.parse("Withdraw: Coins, Dorg Bow, 50 bolts")).contains("Bone bolts"));
		assertTrue(names(ItemListParser.parse("Withdraw: Rune crossbow, Bolts")).contains("Bolts"));
	}

	@Test
	public void placeAndConditionAreNotItems()
	{
		assertEquals(java.util.Arrays.asList("Plague Sample", "Touch Paper"), names(ItemListParser.parse(
			"Withdraw at Varrock east bank: Plague Sample, Touch Paper. Bank the bows")));

		List<ItemReq> conditional = ItemListParser.parse("Withdraw: Gold Bar, Amulet Mould, Ball of Wool. "
			+ "If 85 Crafting also withdraw Karamja Gloves, Chisel & 29,000 Chaos Runes");
		assertEquals(java.util.Arrays.asList("Gold Bar", "Amulet Mould", "Ball of Wool"), names(conditional));

		List<ItemReq> optional = ItemListParser.parse(
			"Withdraw: Gold Bar, Strange Fruits(Optional) (11 Inventory Slots)+Food");
		assertFalse(names(optional).contains("Strange Fruits"));
		assertEquals(2, optional.size());
	}

	@Test
	public void trailingNotesYieldTheirItems()
	{
		// the arrow is what the wikitext "->" becomes
		List<ItemReq> fill = ItemListParser.parse(
			"Withdraw: Dramen Staff, Potato Cactus (22 Inventory Slots) → 6 Karamwbwans");
		assertEquals(3, fill.size());
		assertEquals("Potato Cactus", fill.get(1).getName());
		assertEquals(6, fill.get(2).getQuantity());
		assertEquals("Cooked karambwan", ItemAliases.canonical(fill.get(2).getNormalized()));

		List<ItemReq> glass = ItemListParser.parse(
			"Withdraw: 3x Giant Seaweed & 18 Sand with Superglass make runes in the Rune Pouch");
		assertEquals(18, find(glass, "Sand").getQuantity());
		assertEquals(10, find(glass, "Air rune").getQuantity());
		assertEquals(2, find(glass, "Astral rune").getQuantity());

		List<ItemReq> leaf = ItemListParser.parse("Collect 1x Palm leaf Drop 5x [Karamja Hard Diary]");
		assertEquals(java.util.Collections.singletonList("Palm leaf"), names(leaf));
	}

	@Test
	public void curatedListsAcceptAnyDoseLikeEveryOtherList()
	{
		List<ItemReq> items = ItemListParser.parse(
			"Withdraw: Coins, Dueling Ring, a knife, log or axe, ardy cloak, dramen staff.");
		assertTrue(find(items, "Ring of dueling(8)").getAlternatives().contains("Ring of dueling(3)"));
		assertTrue(find(items, "Ardougne cloak 1").getAlternatives().contains("Ardougne cloak 4"));

		List<ItemReq> poison = ItemListParser.parse("Withdraw: Teleport Runes, Coins, Death Runes, Antipoison, "
			+ "Super Antipoison, Scrying Orb If 56/57/58 Magic; Wizard Mind Bomb, (9/10 Inventory Slots) + Food");
		assertTrue(find(poison, "Antipoison(4)").getAlternatives().contains("Antipoison(1)"));
		assertTrue(find(poison, "Superantipoison(4)").getAlternatives().contains("Superantipoison(2)"));
	}

	@Test
	public void quantityForms()
	{
		List<ItemReq> items = ItemListParser.parseItemsNeeded(
			"1.1k gold bars, 16k feathers, 5-10 lobsters, 290gp, 2-dose potions, 99999999999 nails");
		assertEquals(1100, find(items, "gold bars").getQuantity());
		assertEquals(16000, find(items, "feathers").getQuantity());
		assertEquals(5, find(items, "lobsters").getQuantity());
		assertEquals("5-10", find(items, "lobsters").getQuantityLabel());
		assertEquals(290, find(items, "Coins").getQuantity());
		assertEquals(1, find(items, "2-dose potions").getQuantity());
		// a count too large for an int is "a great many", not "one"
		assertTrue(find(items, "nails").getQuantity() >= 1_000_000);
	}

	@Test
	public void proseInAnItemsNoteIsNotAnItem()
	{
		List<ItemReq> items = ItemListParser.parseItemsSuffix(
			"x (Items: 781 gp, will need to bank while buying from Aemad and while stealing cakes/silk)");
		assertEquals(1, items.size());
		assertEquals(781, items.get(0).getQuantity());
		// "Will and testament" is an item whose name starts like that prose
		assertEquals(1, ItemListParser.parseItemsNeeded("Will and testament").size());
	}
}
