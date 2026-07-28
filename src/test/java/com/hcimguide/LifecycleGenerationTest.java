package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the activation-generation rule.
 *
 * <p>RuneLite reuses the plugin instance across disable and re-enable, so a
 * plain active flag cannot tell a callback from a previous activation apart
 * from a live one. These tests pin that behaviour.</p>
 */
public class LifecycleGenerationTest
{
	@Test
	public void isNotActiveBeforeFirstBegin()
	{
		assertFalse(new LifecycleGeneration().isActive());
	}

	@Test
	public void ownTokenIsCurrentWhileActive()
	{
		LifecycleGeneration lifecycle = new LifecycleGeneration();
		long token = lifecycle.begin();
		assertTrue(lifecycle.isActive());
		assertTrue(lifecycle.isCurrent(token));
	}

	@Test
	public void tokenStopsBeingCurrentAfterEnd()
	{
		LifecycleGeneration lifecycle = new LifecycleGeneration();
		long token = lifecycle.begin();
		lifecycle.end();
		assertFalse(lifecycle.isActive());
		assertFalse(lifecycle.isCurrent(token));
	}

	/** The race this class exists for. */
	@Test
	public void previousActivationCannotAffectTheNextOne()
	{
		LifecycleGeneration lifecycle = new LifecycleGeneration();
		long first = lifecycle.begin();
		lifecycle.end();
		long second = lifecycle.begin();

		assertNotEquals(first, second);
		assertFalse("work from the old activation must be rejected",
			lifecycle.isCurrent(first));
		assertTrue(lifecycle.isCurrent(second));
	}

	/**
	 * Shutdown cleanup is queued AFTER end() and must still run while the
	 * plugin stays disabled - but if a re-enable outruns the queued cleanup,
	 * the cleanup must yield so it cannot wipe the new activation's state.
	 * The cleanup captures {@code current()} after end() and compares it
	 * against {@code current()} at execution time; this pins that pattern.
	 */
	@Test
	public void shutdownCleanupYieldsOnceANewActivationBegins()
	{
		LifecycleGeneration lifecycle = new LifecycleGeneration();
		lifecycle.begin();
		lifecycle.end();
		long closing = lifecycle.current();

		// plugin still disabled when the queued cleanup runs: it may proceed
		assertTrue("cleanup must run while no new activation exists",
			lifecycle.current() == closing);

		// stop -> start -> old callback: the re-enable wins the race
		lifecycle.begin();
		assertNotEquals("cleanup queued by the closing activation must yield "
			+ "to the newer activation", closing, lifecycle.current());
	}

	@Test
	public void repeatedCyclesNeverReuseAToken()
	{
		LifecycleGeneration lifecycle = new LifecycleGeneration();
		long previous = lifecycle.begin();
		for (int i = 0; i < 100; i++)
		{
			lifecycle.end();
			long token = lifecycle.begin();
			assertTrue("generation must advance", token > previous);
			assertFalse("older token must never match again",
				lifecycle.isCurrent(previous));
			previous = token;
		}
	}
}
