package com.hcimguide;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional hand-off to the community "Shortest Path" Plugin Hub plugin:
 * when enabled, the current objective is sent over RuneLite's PluginMessage
 * bus so that plugin draws its real, collision-aware tile path (with all
 * the transport/teleport options the USER configured over there).
 *
 * Deliberately loose coupling: this is a broadcast, not an API call. If
 * Shortest Path isn't installed, nobody is listening and the message
 * vanishes at zero cost - no dependency, no reflection, no error. That's
 * also why this plugin doesn't reimplement pathfinding itself: a real
 * pathfinder needs the game's full collision map (megabytes of data kept
 * current by that plugin already).
 *
 * Dedup: a target is only re-sent when it CHANGES, and "clear" is only
 * sent when something was sent before - the bus never sees per-tick spam.
 */
@Singleton
public class PathfinderIntegration
{
	private static final Logger log = LoggerFactory.getLogger(PathfinderIntegration.class);

	/** Namespace/payload contract published by the Shortest Path plugin. */
	static final String NAMESPACE = "shortestpath";

	private final EventBus eventBus;

	private WorldPoint lastSent; // client thread only

	@Inject
	public PathfinderIntegration(EventBus eventBus)
	{
		this.eventBus = eventBus;
	}

	/**
	 * Ignore target drift below this many tiles: a pinned NPC wandering its
	 * patrol must not make the pathfinder replan every tick. Kept small so
	 * the drawn path still tracks a moving target near-realtime.
	 */
	private static final int RESEND_DEADBAND_TILES = 2;

	/** Send a path target (client thread). Null clears. */
	public void setTarget(WorldPoint target)
	{
		if (target == null)
		{
			clear();
			return;
		}
		if (lastSent != null
			&& target.getPlane() == lastSent.getPlane()
			&& Math.max(Math.abs(target.getX() - lastSent.getX()),
				Math.abs(target.getY() - lastSent.getY())) <= RESEND_DEADBAND_TILES)
		{
			return;
		}
		lastSent = target;
		try
		{
			Map<String, Object> data = new HashMap<>();
			data.put("target", target);
			eventBus.post(new PluginMessage(NAMESPACE, "path", data));
		}
		catch (Exception e)
		{
			// never let an integration hiccup touch the guide itself
			log.debug("Shortest Path hand-off failed", e);
		}
	}

	/** Remove the drawn path, if any was requested (client thread). */
	public void clear()
	{
		if (lastSent == null)
		{
			return;
		}
		lastSent = null;
		try
		{
			eventBus.post(new PluginMessage(NAMESPACE, "clear"));
		}
		catch (Exception e)
		{
			log.debug("Shortest Path clear failed", e);
		}
	}
}
