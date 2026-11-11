package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Small pure rules of {@link HcimGuidePlugin} that need no client. */
public class PluginCoreRegressionTest
{
	@Test
	public void questHelperStateIsReadOnTheFirstCheck()
	{
		// a never-filled cache must not look fresh, on tick 0 or on any later
		// one (a far "never" sentinel overflows the age subtraction)
		for (int tick : new int[]{0, 1, 19, 20, 21, 1_000, 1_000_000, Integer.MAX_VALUE})
		{
			assertFalse("tick " + tick, HcimGuidePlugin.questHelperCacheFresh(
				tick, HcimGuidePlugin.QUEST_HELPER_NEVER_CHECKED));
		}
	}

	@Test
	public void questHelperStateIsCachedBetweenChecks()
	{
		int checked = 500;
		assertTrue(HcimGuidePlugin.questHelperCacheFresh(checked, checked));
		assertTrue(HcimGuidePlugin.questHelperCacheFresh(
			checked + HcimGuidePlugin.QUEST_HELPER_RECHECK_TICKS - 1, checked));
		assertFalse(HcimGuidePlugin.questHelperCacheFresh(
			checked + HcimGuidePlugin.QUEST_HELPER_RECHECK_TICKS, checked));
	}
}
