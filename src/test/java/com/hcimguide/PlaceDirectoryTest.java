package com.hcimguide;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlaceDirectoryTest
{
	private final PlaceDirectory directory = new PlaceDirectory(new Gson());

	@Test
	public void resolvesTitheFarmWithoutNpc()
	{
		StepLocationHint hint = directory.find("Do Tithe Farm until 62 Farming");
		assertNotNull(hint);
		assertEquals("Tithe Farm", hint.getLabel());
		assertEquals(1801, hint.getPoint().getX());
		assertEquals(3505, hint.getPoint().getY());
	}

	@Test
	public void finalAndMostSpecificDestinationWins()
	{
		StepLocationHint hint = directory.find(
			"Teleport to Varrock, then bank at Varrock East Bank");
		assertNotNull(hint);
		assertEquals("Varrock East Bank", hint.getLabel());
	}

	@Test
	public void broadRegionAliasResolvesToArrivalHub()
	{
		StepLocationHint hint = directory.find(
			"Travel east to Regulus Cento & travel to Varlamore");
		assertNotNull(hint);
		assertEquals("Civitas illa Fortis", hint.getLabel());
	}

	@Test
	public void apostropheVariantsNormalize()
	{
		assertEquals("Wizards' Tower", directory.find("Head to Wizards’ Tower").getLabel());
		assertEquals("Rogues' Den", directory.find("Enter Rogues Den").getLabel());
	}

	@Test
	public void unrelatedTextDoesNotResolve()
	{
		assertNull(directory.find("Withdraw coins, hammer and a saw"));
	}

	@Test
	public void detectsMovementIntent()
	{
		assertTrue(PlaceDirectory.hasMovementIntent("Minigame teleport to Tithe Farm"));
		assertTrue(PlaceDirectory.hasMovementIntent("Head north to the church"));
		assertTrue(PlaceDirectory.hasMovementIntent("Take the portal to Soul Wars"));
		assertFalse(PlaceDirectory.hasMovementIntent("Buy a seed box from Tithe Farm"));
	}
	@Test
	public void laterUnknownTravelDoesNotReuseEarlierPlace()
	{
		assertNull(directory.find(
			"Go to Ferox Enclave, then take the portal to Soul Wars"));
	}

	@Test
	public void ambiguousItemAndGenericPlaceWordsDoNotCreatePins()
	{
		assertNull(directory.find("Withdraw a digsite pendant from the bank"));
		assertNull(directory.find("Withdraw an Ardougne cloak from the bank"));
		assertNull(directory.find("Run to the monastery"));
		assertEquals("Ardougne Monastery",
			directory.find("Head to Ardougne Monastery").getLabel());
	}

	@Test
	public void singleWordPlacesNeedClearLocationContextWithoutMovement()
	{
		assertEquals("Varrock", directory.find("Bank in Varrock").getLabel());
		assertEquals("Wintertodt Camp",
			directory.find("Do Wintertodt until 90 Firemaking").getLabel());
	}

}
