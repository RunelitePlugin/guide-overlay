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
}
