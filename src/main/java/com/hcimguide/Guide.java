package com.hcimguide;

import java.util.ArrayList;
import java.util.List;

public class Guide
{
	private final List<GuideEpisode> episodes = new ArrayList<>();

	public List<GuideEpisode> getEpisodes()
	{
		return episodes;
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
