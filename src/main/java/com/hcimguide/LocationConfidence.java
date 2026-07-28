package com.hcimguide;

/** User-facing confidence for a resolved destination. */
enum LocationConfidence
{
	MANUAL("Manual"),
	EXACT("Exact"),
	HIGH("High"),
	MEDIUM("Medium"),
	LOW("Low");

	private final String displayName;

	LocationConfidence(String displayName)
	{
		this.displayName = displayName;
	}

	String displayName()
	{
		return displayName;
	}
}
