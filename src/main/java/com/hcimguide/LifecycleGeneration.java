package com.hcimguide;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks which activation of the plugin is current.
 *
 * <p>RuneLite reuses the same plugin instance when a plugin is disabled and
 * enabled again, so a plain "am I active" flag is not enough to protect
 * asynchronous work. Consider:</p>
 *
 * <pre>
 * work starts -> plugin disabled -> plugin enabled -> old callback finishes
 * </pre>
 *
 * <p>By the time the old callback runs the flag is true again, so it passes the
 * check and mutates the NEW session with results belonging to the old one. A
 * generation number that increments on both start and stop closes that hole: the
 * callback captured generation N, the current generation is N+2, so it declines
 * to run.</p>
 *
 * <p>This class is deliberately free of RuneLite and Swing dependencies so it can
 * be unit tested directly.</p>
 */
final class LifecycleGeneration
{
	private final AtomicLong generation = new AtomicLong();
	private volatile boolean active;

	/** Mark a new activation and return its generation token. */
	long begin()
	{
		long g = generation.incrementAndGet();
		active = true;
		return g;
	}

	/**
	 * End the current activation. The generation advances here too, so work
	 * captured during the activation that just ended can never match again,
	 * even if the plugin is immediately re-enabled.
	 */
	void end()
	{
		active = false;
		generation.incrementAndGet();
	}

	/** Token for the activation running right now. */
	long current()
	{
		return generation.get();
	}

	/** True only while {@code token} still refers to the live activation. */
	boolean isCurrent(long token)
	{
		return active && generation.get() == token;
	}

	/** True while any activation is live. Equivalent to the old active flag. */
	boolean isActive()
	{
		return active;
	}
}
