package com.hcimguide;

/** One runtime-only phase inside a persisted source guide row. */
final class StepExecutionPhase
{
	private final String id;
	private final String text;
	private final StepPhaseParser.Kind kind;
	private final StepLocationPlan locationPlan;
	private final String entityTarget;
	private final StepCondition condition;

	StepExecutionPhase(String id, String text, StepPhaseParser.Kind kind,
		StepLocationPlan locationPlan, String entityTarget, StepCondition condition)
	{
		this.id = id;
		this.text = text;
		this.kind = kind;
		this.locationPlan = locationPlan;
		this.entityTarget = entityTarget;
		this.condition = condition;
	}

	String getId()
	{
		return id;
	}

	String getText()
	{
		return text;
	}

	StepPhaseParser.Kind getKind()
	{
		return kind;
	}

	StepLocationPlan getLocationPlan()
	{
		return locationPlan;
	}

	String getEntityTarget()
	{
		return entityTarget;
	}

	StepCondition getCondition()
	{
		return condition;
	}

	boolean isTravel()
	{
		return kind == StepPhaseParser.Kind.TRAVEL;
	}
}
