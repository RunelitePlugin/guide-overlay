package com.hcimguide;

import com.google.gson.Gson;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TransportResolverTest
{
	private final TransportResolver resolver = new TransportResolver(new Gson());

	@Test
	public void resolvesFairyRingOnlyWithTransportContext()
	{
		StepLocationHint hint = resolver.find("POH -> BKR, then run east");
		assertEquals("Fairy ring BKR", hint.getLabel());
		assertEquals(new WorldPoint(3468, 3433, 0), hint.getPoint());
		assertTrue(hint.isTransport());
		assertNull(resolver.find("Buy air runes and bank them"));
	}

	@Test
	public void resolvesCodeBeforeFairyRingLabel()
	{
		StepLocationHint hint = resolver.find("Use BIP fairy ring for the next step");
		assertEquals("Fairy ring BIP", hint.getLabel());
	}

	@Test
	public void resolvesExplicitJewelryDestination()
	{
		StepLocationHint hint = resolver.find("Ring of dueling to Ferox Enclave");
		assertEquals("Ring of dueling — Ferox Enclave", hint.getLabel());
		assertEquals(new WorldPoint(3151, 3635, 0), hint.getPoint());
	}

	@Test
	public void itemListDoesNotBecomeATransportDestination()
	{
		assertNull(resolver.find("Withdraw a Digsite pendant, rope, and food"));
		assertFalse(TransportResolver.hasTransportIntent("Withdraw a Digsite pendant, rope, and food"));
	}

	@Test
	public void transportIntentRecognizesNonTeleportTravel()
	{
		assertTrue(TransportResolver.hasTransportIntent("Take the Quetzal to Tal Teklan"));
		assertTrue(TransportResolver.hasTransportIntent("Charter a ship to Port Tyras"));
		assertFalse(TransportResolver.hasTransportIntent("Buy ten buckets and a hammer"));
	}

	@Test
	public void recognizesCommonTeleportShorthandWithoutColoringInventoryLists()
	{
		assertTrue(TransportResolver.hasTransportIntent("Minigame tele to Tempoross"));
		assertTrue(TransportResolver.hasTransportIntent("Use your giantsoul amulet"));
		assertTrue(TransportResolver.hasTransportIntent("Boat to Port Khazard"));
		assertTrue(TransportResolver.hasTransportIntent("Go through the portal"));
		assertFalse(TransportResolver.hasTransportIntent("Withdraw your games necklace"));
	}

	@Test
	public void resolvesNewVerifiedTransportEntries()
	{
		assertEquals("Minigame teleport — Mage Training Arena",
			resolver.find("Minigame tele to Mage Training Arena").getLabel());
		assertEquals("Giantsoul amulet — Royal Titans",
			resolver.find("Use your giantsoul amulet").getLabel());
		assertEquals("Chasm teleport scroll — Yama",
			resolver.find("Use a chasm teleport scroll").getLabel());
	}
}
