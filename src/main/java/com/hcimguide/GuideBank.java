package com.hcimguide;

import java.util.ArrayList;
import java.util.List;

/**
 * A "Bank" section of the guide (or any other sub-section inside an episode,
 * e.g. introductory notes that appear before the first bank heading).
 */
public class GuideBank
{
	private final String id;
	private final String title;
	private final int episodeNumber;
	private final List<GuideStep> steps = new ArrayList<>();
	/**
	 * Video guide (YouTube/Streamable) for this whole section, from the
	 * "Video Guide: &lt;url&gt;" lines and {{Youtube|id}} templates the wiki
	 * places between checklists. Display metadata only - never part of any
	 * step text, so progress keys are unaffected.
	 */
	private String videoUrl;

	public GuideBank(String id, String title, int episodeNumber)
	{
		this.id = id;
		this.title = title;
		this.episodeNumber = episodeNumber;
	}

	public String getId()
	{
		return id;
	}

	public String getTitle()
	{
		return title;
	}

	public int getEpisodeNumber()
	{
		return episodeNumber;
	}

	public List<GuideStep> getSteps()
	{
		return steps;
	}

	public String getVideoUrl()
	{
		return videoUrl;
	}

	void setVideoUrl(String videoUrl)
	{
		this.videoUrl = videoUrl;
	}
}
