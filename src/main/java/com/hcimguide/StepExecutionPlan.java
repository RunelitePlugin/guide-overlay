package com.hcimguide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable runtime execution plan for one original checklist row. */
final class StepExecutionPlan
{
	private final String parentStepKey;
	private final StepPhaseParser.Mode mode;
	private final List<StepExecutionPhase> phases;

	StepExecutionPlan(String parentStepKey, StepPhaseParser.Mode mode,
		List<StepExecutionPhase> phases)
	{
		this.parentStepKey = parentStepKey;
		this.mode = mode;
		this.phases = Collections.unmodifiableList(new ArrayList<>(phases));
	}

	String getParentStepKey()
	{
		return parentStepKey;
	}

	StepPhaseParser.Mode getMode()
	{
		return mode;
	}

	List<StepExecutionPhase> getPhases()
	{
		return phases;
	}

	int size()
	{
		return phases.size();
	}

	StepExecutionPhase get(int index)
	{
		if (phases.isEmpty())
		{
			return null;
		}
		return phases.get(Math.max(0, Math.min(index, phases.size() - 1)));
	}

	int indexOfId(String id)
	{
		if (id == null)
		{
			return -1;
		}
		for (int i = 0; i < phases.size(); i++)
		{
			if (id.equals(phases.get(i).getId()))
			{
				return i;
			}
		}
		return -1;
	}

	boolean isVirtualized()
	{
		return phases.size() > 1;
	}
}
