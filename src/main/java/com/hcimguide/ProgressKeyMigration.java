package com.hcimguide;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Compatibility migrations for progress keys produced by older parsers. */
final class ProgressKeyMigration
{
	private ProgressKeyMigration()
	{
	}

	/**
	 * Earlier parser versions placed every step in an episode-wide "Notes"
	 * section when the wiki's visual Bank labels were not recognized. Once the
	 * labels parse correctly, section IDs become E#.B# and therefore stable step
	 * keys change. Replace matching legacy Notes keys in guide order.
	 *
	 * KNOWN BOUNDED EDGE: legacy occurrence indices were assigned in document
	 * order; this replay iterates bank-grouped order. They differ only when a
	 * repeated bank marker CONTINUES an earlier section AND the identical step
	 * text also appears in an intervening bank - in that narrow case one
	 * duplicate-text checkbox can land on the wrong twin. Every other key
	 * migrates exactly; the user re-ticks at most one step.
	 *
	 * @return true when the supplied set was changed
	 */
	static boolean migrateLegacyNotes(Guide guide, Set<String> completedSteps)
	{
		if (guide == null || completedSteps == null || guide.numberedBanks() == 0)
		{
			return false;
		}

		boolean migrated = false;
		Map<String, Integer> occurrence = new HashMap<>();
		for (GuideEpisode episode : guide.getEpisodes())
		{
			String notesId = "E" + episode.getNumber() + "-notes";
			for (GuideBank bank : episode.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					String occKey = notesId + " " + step.getText();
					int occ = occurrence.merge(occKey, 1, Integer::sum) - 1;
					String legacyKey = notesId + "#"
						+ Integer.toHexString(step.getText().hashCode()) + "#" + occ;
					if (!legacyKey.equals(step.getKey()) && completedSteps.remove(legacyKey))
					{
						completedSteps.add(step.getKey());
						migrated = true;
					}
				}
			}
		}
		return migrated;
	}
}
