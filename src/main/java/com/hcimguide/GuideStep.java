package com.hcimguide;

/**
 * A single actionable line of the guide (one bullet point).
 */
public class GuideStep
{
	private final String key;
	private final String text;
	private final int depth;
	private final String bankId;
	/**
	 * First whitelisted video link (YouTube/Streamable) this step references,
	 * or null. Display metadata only - NEVER part of the step text, so keys
	 * (users' saved progress) are unaffected by link changes.
	 */
	private String videoUrl;
	/**
	 * The step's complete item list when the guide source supplies one
	 * separately from the text (a JSON guide's items_needed), or null. Display
	 * metadata only - the text may show a shortened copy, and the key is built
	 * from the text alone.
	 */
	private String itemsNeeded;

	public GuideStep(String key, String text, int depth, String bankId)
	{
		this.key = key;
		this.text = text;
		this.depth = depth;
		this.bankId = bankId;
	}

	/**
	 * Stable identifier used to persist checkbox state. Built from the bank id,
	 * a hash of the step text and the occurrence index of identical text within
	 * the bank, so it survives unrelated edits to the guide.
	 */
	public String getKey()
	{
		return key;
	}

	public String getText()
	{
		return text;
	}

	/** Nesting depth; 0 = top-level bullet, 1 = sub-bullet, ... */
	public int getDepth()
	{
		return depth;
	}

	public String getBankId()
	{
		return bankId;
	}

	public String getVideoUrl()
	{
		return videoUrl;
	}

	void setVideoUrl(String videoUrl)
	{
		this.videoUrl = videoUrl;
	}

	public String getItemsNeeded()
	{
		return itemsNeeded;
	}

	void setItemsNeeded(String itemsNeeded)
	{
		this.itemsNeeded = itemsNeeded;
	}
}
