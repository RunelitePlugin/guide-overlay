package com.hcimguide;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProgressKeyMigrationTest
{
	@Test
	public void migratesEpisodeWideNotesKeysToBankKeysInGuideOrder()
	{
		Guide guide = new WikitextParser().parse("== Episode 1 - x ==\n"
			+ "'''Bank 1'''\n* same step\n* unique\n"
			+ "'''Bank 2'''\n* same step\n");
		Set<String> completed = new HashSet<>();
		completed.add(legacy("E1-notes", "same step", 0));
		completed.add(legacy("E1-notes", "same step", 1));

		assertTrue(ProgressKeyMigration.migrateLegacyNotes(guide, completed));
		assertTrue(completed.contains(guide.getEpisodes().get(0).getBanks().get(0).getSteps().get(0).getKey()));
		assertTrue(completed.contains(guide.getEpisodes().get(0).getBanks().get(1).getSteps().get(0).getKey()));
		assertFalse(completed.contains(legacy("E1-notes", "same step", 0)));
		assertFalse(completed.contains(legacy("E1-notes", "same step", 1)));
	}

	@Test
	public void leavesCorrectBankKeysAndUnrelatedKeysUntouched()
	{
		Guide guide = new WikitextParser().parse("== Episode 1 - x ==\n'''Bank 1'''\n* step\n");
		String current = guide.getEpisodes().get(0).getBanks().get(0).getSteps().get(0).getKey();
		Set<String> completed = new HashSet<>();
		completed.add(current);
		completed.add("unrelated");

		assertFalse(ProgressKeyMigration.migrateLegacyNotes(guide, completed));
		assertTrue(completed.contains(current));
		assertTrue(completed.contains("unrelated"));
	}

	private static String legacy(String notesId, String text, int occurrence)
	{
		return notesId + "#" + Integer.toHexString(text.hashCode()) + "#" + occurrence;
	}
}
