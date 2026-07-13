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

	public boolean isEmpty()
	{
		return totalSteps() == 0;
	}
}
