package com.hcimguide;

import java.util.ArrayList;
import java.util.List;

public class Guide
{
	private final List<GuideEpisode> episodes = new ArrayList<>();

	/**
	 * Old step key -&gt; current step key, for steps whose TEXT was produced
	 * differently by an earlier parser version (e.g. the JSON parser once
	 * joined formatting runs with spaces, splitting words). Lets progress
	 * recorded under the old keys be replayed onto the fixed keys. Empty for
	 * parsers that never changed a step's text.
	 */
	private final java.util.Map<String, String> legacyStepKeys = new java.util.HashMap<>();

	public List<GuideEpisode> getEpisodes()
	{
		return episodes;
	}

	public java.util.Map<String, String> getLegacyStepKeys()
	{
		return legacyStepKeys;
	}

	public int totalSteps()
	{
		int n = 0;
		for (GuideEpisode e : episodes)
		{
			n += e.totalSteps();
		}
		return n;
	}

	public int totalSections()
	{
		int n = 0;
		for (GuideEpisode e : episodes)
		{
			n += e.getBanks().size();
		}
		return n;
	}

	/** Number of sections positively identified as numbered Bank markers. */
	public int numberedBanks()
	{
		int n = 0;
		for (GuideEpisode e : episodes)
		{
			for (GuideBank b : e.getBanks())
			{
				if (b.getId().matches("E[0-9]+\\.B[0-9]+[A-Z]?"))
				{
					n++;
				}
			}
		}
		return n;
	}

	public boolean isEmpty()
	{
		return totalSteps() == 0;
	}
}
