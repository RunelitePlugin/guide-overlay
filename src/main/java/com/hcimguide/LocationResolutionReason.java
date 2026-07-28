package com.hcimguide;

/** Diagnostic result used by the unresolved-location audit. */
enum LocationResolutionReason
{
	RESOLVED("Resolved"),
	NO_LOCATION_NEEDED("No location needed"),
	UNKNOWN_NAMED_PLACE("Unknown named place"),
	AMBIGUOUS_GENERIC_PLACE("Ambiguous generic place"),
	RELATIVE_DIRECTION_ONLY("Relative direction only"),
	MULTIPLE_DESTINATIONS("Multiple destinations"),
	UNKNOWN_TRANSPORT_CODE("Unknown transport code"),
	QUEST_STAGE("Quest stage"),
	DYNAMIC_NEAREST_REQUIRED("Dynamic nearest-location lookup required"),
	ENTITY_LOCATION_UNKNOWN("Entity location unknown");

	private final String displayName;

	LocationResolutionReason(String displayName)
	{
		this.displayName = displayName;
	}

	String displayName()
	{
		return displayName;
	}
}
