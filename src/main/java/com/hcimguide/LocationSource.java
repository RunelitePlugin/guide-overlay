package com.hcimguide;

/** How a location waypoint was obtained. */
enum LocationSource
{
	USER_PIN("Manual pin"),
	AUTHORED_WAYPOINT("Authored waypoint"),
	TRANSPORT("Transport resolver"),
	NAMED_PLACE("Named location"),
	LIVE_NPC("Live NPC"),
	LIVE_OBJECT("Live object"),
	STORED_ENTITY("Stored entity location"),
	INHERITED_CONTEXT("Inherited context");

	private final String displayName;

	LocationSource(String displayName)
	{
		this.displayName = displayName;
	}

	String displayName()
	{
		return displayName;
	}
}
