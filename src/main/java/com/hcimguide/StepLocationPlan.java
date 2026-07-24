package com.hcimguide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable ordered location plan for a guide step. */
final class StepLocationPlan
{
	private final String stepKey;
	private final List<StepLocationHint> waypoints;
	private final LocationResolutionReason reason;
	private final List<String> unresolvedCandidates;

	StepLocationPlan(String stepKey, List<StepLocationHint> waypoints)
	{
		this(stepKey, waypoints, waypoints == null || waypoints.isEmpty()
			? LocationResolutionReason.NO_LOCATION_NEEDED : LocationResolutionReason.RESOLVED,
			Collections.emptyList());
	}

	StepLocationPlan(String stepKey, List<StepLocationHint> waypoints,
		LocationResolutionReason reason, List<String> unresolvedCandidates)
	{
		this.stepKey = stepKey;
		List<StepLocationHint> safe = waypoints == null ? Collections.emptyList() : waypoints;
		this.waypoints = Collections.unmodifiableList(new ArrayList<>(safe));
		this.reason = reason == null ? LocationResolutionReason.NO_LOCATION_NEEDED : reason;
		List<String> candidates = unresolvedCandidates == null
			? Collections.emptyList() : unresolvedCandidates;
		this.unresolvedCandidates = Collections.unmodifiableList(new ArrayList<>(candidates));
	}

	String getStepKey()
	{
		return stepKey;
	}

	List<StepLocationHint> getWaypoints()
	{
		return waypoints;
	}

	boolean hasWaypoints()
	{
		return !waypoints.isEmpty();
	}

	int size()
	{
		return waypoints.size();
	}

	StepLocationHint get(int index)
	{
		if (waypoints.isEmpty())
		{
			return null;
		}
		return waypoints.get(Math.max(0, Math.min(index, waypoints.size() - 1)));
	}

	LocationResolutionReason getReason()
	{
		return reason;
	}

	List<String> getUnresolvedCandidates()
	{
		return unresolvedCandidates;
	}

	StepLocationPlan withWaypoints(List<StepLocationHint> replacement)
	{
		return new StepLocationPlan(stepKey, replacement,
			replacement == null || replacement.isEmpty() ? reason : LocationResolutionReason.RESOLVED,
			unresolvedCandidates);
	}
}
