package com.hcimguide;

import java.util.ArrayList;
import java.util.List;

public class GuideEpisode
{
	private final int number;
	private final String title;
	private final List<GuideBank> banks = new ArrayList<>();
	/**
	 * Video guide for the whole episode, from the {{Youtube|id}} embed the
	 * wiki places right under each episode heading. Display metadata only.
	 */
	private String videoUrl;

	public GuideEpisode(int number, String title)
	{
		this.number = number;
		this.title = title;
	}

	public int getNumber()
	{
		return number;
	}

	public String getTitle()
	{
		return title;
	}

	public List<GuideBank> getBanks()
	{
		return banks;
	}

	public String getVideoUrl()
	{
		return videoUrl;
	}

	void setVideoUrl(String videoUrl)
	{
		this.videoUrl = videoUrl;
	}

	public int totalSteps()
	{
		int n = 0;
		for (GuideBank b : banks)
		{
			n += b.getSteps().size();
		}
		return n;
	}
}
