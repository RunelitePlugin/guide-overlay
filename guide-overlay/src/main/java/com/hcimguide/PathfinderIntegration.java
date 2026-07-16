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
	private long lastSentAtNanos;

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
	/**
	 * PluginMessage is a one-way broadcast with no acknowledgement. Re-send a
	 * stable target occasionally so the integration recovers when Shortest Path
	 * starts after this plugin, reloads, or clears its path internally.
	 */
	private static final long KEEPALIVE_NANOS = 15_000_000_000L;

	/** Send a path target (client thread). Null clears. */
	public void setTarget(WorldPoint target)
	{
		setTarget(target, false);
	}

	/**
	 * @param movingTarget true only for a live NPC whose patrol movement should
	 *                     not trigger a full path recalculation every tick
	 */
	public void setTarget(WorldPoint target, boolean movingTarget)
	{
		if (target == null)
		{
			clear();
			return;
		}
		long now = System.nanoTime();
		boolean keepaliveDue = lastSent == null || now - lastSentAtNanos >= KEEPALIVE_NANOS;
		if (!keepaliveDue && lastSent != null && target.getPlane() == lastSent.getPlane())
		{
			int drift = Math.max(Math.abs(target.getX() - lastSent.getX()),
				Math.abs(target.getY() - lastSent.getY()));
			// Static objectives update on ANY tile change. Only a live NPC gets
			// the patrol deadband; otherwise two adjacent guide objectives could
			// incorrectly share a stale path forever.
			if (target.equals(lastSent) || (movingTarget && drift <= RESEND_DEADBAND_TILES))
			{
				return;
			}
		}
		try
		{
			Map<String, Object> data = new HashMap<>();
			data.put("target", target);
			eventBus.post(new PluginMessage(NAMESPACE, "path", data));
			// Commit dedup state only after the message was posted successfully.
			lastSent = target;
			lastSentAtNanos = now;
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
		try
		{
			eventBus.post(new PluginMessage(NAMESPACE, "clear"));
			lastSent = null;
			lastSentAtNanos = 0L;
		}
		catch (Exception e)
		{
			log.debug("Shortest Path clear failed", e);
		}
	}
}
