package com.hcimguide;

import java.util.ArrayList;
import java.util.List;

public class GuideEpisode
{
	private final int number;
	private final String title;
	private final List<GuideBank> banks = new ArrayList<>();

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
