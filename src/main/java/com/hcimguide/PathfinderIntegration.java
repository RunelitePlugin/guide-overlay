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

	@FunctionalInterface
	interface MessageSink
	{
		void post(PluginMessage message);
	}

	private final MessageSink messageSink;

	private WorldPoint lastSent; // client thread only
	private long lastSentAtNanos;
	/**
	 * Exact destination represented by the last successful Shortest Path
	 * hand-off. Overlay rendering is not guaranteed to run on the client
	 * thread, so publish this separately from the client-thread dedup state.
	 */
	private volatile WorldPoint activeTarget;

	@Inject
	public PathfinderIntegration(EventBus eventBus)
	{
		this(eventBus::post);
	}

	/** Package-private seam for deterministic lifecycle/failure tests. */
	PathfinderIntegration(MessageSink messageSink)
	{
		this.messageSink = messageSink;
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

	/**
	 * Force the next {@link #setTarget} to resend even if the destination has
	 * not changed.
	 *
	 * <p>Shortest Path only trims the walked tail when it receives a target and
	 * recomputes from the player's CURRENT position. Without a resend the drawn
	 * path keeps its stale head all the way to the destination.</p>
	 */
	public void forceResend()
	{
		lastSentAtNanos = 0L;
		lastSent = null;
	}

	/**
	 * Destination currently represented by Shortest Path, or {@code null}
	 * after a clear. The compass consumes this snapshot so both displays point
	 * at the same tile, including while that tile is inside the loaded scene.
	 */
	WorldPoint getActiveTarget()
	{
		return activeTarget;
	}

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
			messageSink.post(new PluginMessage(NAMESPACE, "path", data));
			// Commit dedup state only after the message was posted successfully.
			lastSent = target;
			lastSentAtNanos = now;
			activeTarget = target;
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
		if (lastSent == null && activeTarget == null)
		{
			return;
		}
		try
		{
			messageSink.post(new PluginMessage(NAMESPACE, "clear"));
			lastSent = null;
			lastSentAtNanos = 0L;
			activeTarget = null;
		}
		catch (Exception e)
		{
			log.debug("Shortest Path clear failed", e);
		}
	}
}
