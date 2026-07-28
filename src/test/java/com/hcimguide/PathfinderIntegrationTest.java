package com.hcimguide;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PathfinderIntegrationTest
{
	@Test
	public void activeTargetTracksTheLastSuccessfulHandoff()
	{
		List<PluginMessage> messages = new ArrayList<>();
		PathfinderIntegration integration = new PathfinderIntegration(messages::add);
		WorldPoint target = new WorldPoint(3200, 3200, 0);

		integration.setTarget(target);

		assertEquals(1, messages.size());
		assertEquals(target, integration.getActiveTarget());
	}

	@Test
	public void forceResendDoesNotBlankTheCompassSnapshot()
	{
		List<PluginMessage> messages = new ArrayList<>();
		PathfinderIntegration integration = new PathfinderIntegration(messages::add);
		WorldPoint target = new WorldPoint(3200, 3200, 0);
		integration.setTarget(target);

		integration.forceResend();

		assertEquals(target, integration.getActiveTarget());
		integration.setTarget(target);
		assertEquals(2, messages.size());
		assertEquals(target, integration.getActiveTarget());
	}

	@Test
	public void movingNpcDeadbandKeepsCompassOnTheDrawnPathTarget()
	{
		List<PluginMessage> messages = new ArrayList<>();
		PathfinderIntegration integration = new PathfinderIntegration(messages::add);
		WorldPoint drawnTarget = new WorldPoint(3200, 3200, 0);
		integration.setTarget(drawnTarget, true);

		integration.setTarget(new WorldPoint(3201, 3200, 0), true);

		assertEquals(1, messages.size());
		assertEquals(drawnTarget, integration.getActiveTarget());
	}

	@Test
	public void clearAfterForceResendStillClearsTheSnapshot()
	{
		List<PluginMessage> messages = new ArrayList<>();
		PathfinderIntegration integration = new PathfinderIntegration(messages::add);
		integration.setTarget(new WorldPoint(3200, 3200, 0));
		integration.forceResend();

		integration.clear();

		assertEquals(2, messages.size());
		assertNull(integration.getActiveTarget());
	}

	@Test
	public void failedRetargetKeepsTheLastPathAndCompassAligned()
	{
		List<PluginMessage> messages = new ArrayList<>();
		PathfinderIntegration integration = new PathfinderIntegration(message ->
		{
			if (!messages.isEmpty())
			{
				throw new IllegalStateException("simulated hand-off failure");
			}
			messages.add(message);
		});
		WorldPoint accepted = new WorldPoint(3200, 3200, 0);
		integration.setTarget(accepted);

		integration.setTarget(new WorldPoint(3300, 3300, 0));

		assertEquals(accepted, integration.getActiveTarget());
	}
}
