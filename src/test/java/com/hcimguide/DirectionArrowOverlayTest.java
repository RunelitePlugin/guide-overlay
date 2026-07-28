package com.hcimguide;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DirectionArrowOverlayTest
{
	@Test
	public void shortestPathTargetWinsEvenWhenFarTargetExists()
	{
		WorldPoint path = new WorldPoint(3200, 3200, 0);
		WorldPoint far = new WorldPoint(3300, 3300, 0);
		assertEquals(path, DirectionArrowOverlay.selectPrimaryTarget(path, far));
	}

	@Test
	public void farTargetRemainsTheFallback()
	{
		WorldPoint far = new WorldPoint(3300, 3300, 0);
		assertEquals(far, DirectionArrowOverlay.selectPrimaryTarget(null, far));
	}

	@Test
	public void noGuidanceProducesNoPrimaryTarget()
	{
		assertNull(DirectionArrowOverlay.selectPrimaryTarget(null, null));
	}
}
