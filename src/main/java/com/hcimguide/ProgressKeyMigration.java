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

	/**
	 * Replays progress recorded under keys a parser used to produce (the guide
	 * carries an explicit old-key -&gt; new-key map, e.g. after the JSON parser's
	 * run-joining fix changed step texts). Old keys are consumed so the store
	 * stays clean.
	 *
	 * Two safety properties: (1) a legacy key that COLLIDES with some real
	 * step's current key is never touched - genuine new-format progress always
	 * wins over a migration guess; (2) the replay is two-phase against a
	 * snapshot, so chained same-hash mappings (#2-&gt;#1, #1-&gt;#0) can't consume
	 * each other's freshly-migrated keys regardless of map iteration order.
	 *
	 * @return true when the supplied set was changed
	 */
	static boolean migrateLegacyKeys(Guide guide, Set<String> completedSteps)
	{
		if (guide == null || completedSteps == null || guide.getLegacyStepKeys().isEmpty())
		{
			return false;
		}
		Set<String> currentKeys = new java.util.HashSet<>();
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					currentKeys.add(step.getKey());
				}
			}
		}
		Set<String> snapshot = new java.util.HashSet<>(completedSteps);
		Set<String> toRemove = new java.util.HashSet<>();
		Set<String> toAdd = new java.util.HashSet<>();
		for (Map.Entry<String, String> e : guide.getLegacyStepKeys().entrySet())
		{
			if (currentKeys.contains(e.getKey()))
			{
				continue; // a real step owns this key - never steal it
			}
			if (snapshot.contains(e.getKey()))
			{
				toRemove.add(e.getKey());
				toAdd.add(e.getValue());
			}
		}
		boolean changed = completedSteps.removeAll(toRemove);
		changed |= completedSteps.addAll(toAdd);
		return changed;
	}

	/**
	 * Replays progress recorded under keys of steps a parser now SPLITS into
	 * several smaller steps (the guide carries an explicit
	 * old-key -&gt; all-child-keys map): a completed paragraph marks every
	 * split child complete. Old keys are consumed so the store stays clean.
	 *
	 * Same safety properties as {@link #migrateLegacyKeys}: an old key that
	 * collides with a real step's current key is never touched, and the replay
	 * runs two-phase against a snapshot. Run migrateLegacyKeys FIRST - oldest
	 * generation keys chain through the split map's parent keys.
	 *
	 * @return true when the supplied set was changed
	 */
	static boolean migrateSplitKeys(Guide guide, Set<String> progress)
	{
		if (guide == null || progress == null || guide.getLegacySplitKeys().isEmpty())
		{
			return false;
		}
		Set<String> currentKeys = new java.util.HashSet<>();
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					currentKeys.add(step.getKey());
				}
			}
		}
		Set<String> snapshot = new java.util.HashSet<>(progress);
		Set<String> toRemove = new java.util.HashSet<>();
		Set<String> toAdd = new java.util.HashSet<>();
		for (Map.Entry<String, java.util.List<String>> e : guide.getLegacySplitKeys().entrySet())
		{
			if (currentKeys.contains(e.getKey()))
			{
				continue; // a real step owns this key - never steal it
			}
			if (snapshot.contains(e.getKey()))
			{
				toRemove.add(e.getKey());
				toAdd.addAll(e.getValue());
			}
		}
		boolean changed = progress.removeAll(toRemove);
		changed |= progress.addAll(toAdd);
		return changed;
	}
}
