package com.hcimguide;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ItemListParserTest
{
	@Test
	public void parsesWithdrawLists()
	{
		List<ItemReq> w = ItemListParser.parse(
			"Withdraw: Coins, Air Runes, Mind Runes, Bread, Spade, Tinderbox, Air Talisman, Treasure Scroll (8 Inventory slots)");
		assertEquals(8, w.size());
		assertEquals("Coins", w.get(0).getName());
		assertEquals("Treasure Scroll", w.get(7).getName());
		assertEquals(1, w.get(0).getQuantity());

		List<ItemReq> w2 = ItemListParser.parse("Withdraw: Coins, Spade, Feather & Mysterious Orb (4 Inventory Slots)");
		assertEquals(4, w2.size());
		assertEquals("Mysterious Orb", w2.get(3).getName());
	}

	@Test
	public void parsesGatherSteps()
	{
		List<ItemReq> g = ItemListParser.parse("Collect 3x Logs [Tree Gnome Village]");
		assertEquals(3, g.get(0).getQuantity());
		assertEquals("Logs", g.get(0).getName());

		assertEquals("Beer", ItemListParser.parse("Take the Beer").get(0).getName());
		assertEquals("Leather Boots",
			ItemListParser.parse("Take Leather Boots off the table & wield").get(0).getName());
		assertEquals("Cheese",
			ItemListParser.parse("Collect Cheese from Aggie [Witch's House]").get(0).getName());
		assertEquals(5,
			ItemListParser.parse("Buy 5 Jugs of wine. Hop worlds and buy 10 in total.").get(0).getQuantity());
	}

	@Test
	public void ignoresNonItemSteps()
	{
		assertNull(ItemListParser.parse("Take Everything (Make sure you get a feather)"));
		assertNull(ItemListParser.parse("Talk to Father Aereck (3,1) [Restless Ghost]"));
		assertNull(ItemListParser.parse("Mine 10x Clay at varrock west mine"));
	}

	@Test
	public void nameEquivalenceHandlesPlurals()
	{
		assertTrue(ItemReq.namesEquivalent("Air Runes", "Air rune"));
		assertTrue(ItemReq.namesEquivalent("Coins", "Coins"));
		assertTrue(ItemReq.namesEquivalent("Jugs of wine", "Jug of wine"));
		assertFalse(ItemReq.namesEquivalent("Bread", "Bones"));
	}
}
