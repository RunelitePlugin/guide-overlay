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

	/**
	 * Old step key -&gt; ALL current step keys it was split into, for steps a
	 * parser now divides into several smaller steps (e.g. the JSON parser
	 * splitting paragraph steps into one step per sentence). Progress recorded
	 * under the old key replays onto every child, so a paragraph the user had
	 * completed stays fully completed after the split. Empty for parsers that
	 * never split a step.
	 */
	private final java.util.Map<String, java.util.List<String>> legacySplitKeys = new java.util.HashMap<>();

	public List<GuideEpisode> getEpisodes()
	{
		return episodes;
	}

	public java.util.Map<String, String> getLegacyStepKeys()
	{
		return legacyStepKeys;
	}

	public java.util.Map<String, java.util.List<String>> getLegacySplitKeys()
	{
		return legacySplitKeys;
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

	private static final java.util.regex.Pattern NUMBERED_BANK_ID =
		java.util.regex.Pattern.compile("E[0-9]+\\.B[0-9]+[A-Z]?");

	/** Number of sections positively identified as numbered Bank markers. */
	public int numberedBanks()
	{
		int n = 0;
		for (GuideEpisode e : episodes)
		{
			for (GuideBank b : e.getBanks())
			{
				if (NUMBERED_BANK_ID.matcher(b.getId()).matches())
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

	/**
	 * Set when a safety cap (step count, episode count, sections per
	 * episode) discarded part of the source during parsing, so the UI can
	 * say "this import is incomplete" instead of presenting a truncated
	 * guide as the whole thing. Never persisted - recomputed on every parse.
	 */
	private boolean truncated;

	public void markTruncated()
	{
		truncated = true;
	}

	public boolean isTruncated()
	{
		return truncated;
	}
}
