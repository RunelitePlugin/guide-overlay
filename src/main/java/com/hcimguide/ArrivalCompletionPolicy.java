package com.hcimguide;

/**
 * Conservative policy for completing a checklist step from player arrival.
 *
 * <p>Location guidance is broader than proof of completion: an NPC marker,
 * inherited area, or destination attached to a compound instruction can help
 * the player navigate without proving the instruction is finished. This class
 * keeps those concerns separate and permits completion only for an explicit,
 * movement-only leaf step with a trustworthy destination.</p>
 */
final class ArrivalCompletionPolicy
{
	/**
	 * Why arrival may or may not complete a step. One value per refusal so a
	 * "why didn't it check off?" report is answerable from a debug log
	 * instead of re-deriving the whole predicate chain by hand.
	 */
	enum Decision
	{
		ELIGIBLE,
		NO_RESOLVED_PLAN,
		HAS_SEPARATE_CONDITION,
		ENTITY_INTERACTION,
		STRUCTURAL_PARENT,
		NOT_PURE_TRAVEL,
		INFERRED_LOCATION,
		UNTRUSTED_CONFIDENCE,
		UNTRUSTED_SOURCE
	}

	private ArrivalCompletionPolicy()
	{
	}

	static boolean isPureTravelText(String text)
	{
		return StepPhaseParser.parse(text).getMode() == StepPhaseParser.Mode.PURE_TRAVEL;
	}

	static boolean canComplete(GuideStep step, StepLocationPlan plan,
		boolean hasCondition, boolean hasEntityTarget, boolean structuralParent)
	{
		return step != null && canCompleteText(step.getText(), plan,
			hasCondition, hasEntityTarget, structuralParent);
	}

	static boolean canCompleteText(String text, StepLocationPlan plan,
		boolean hasCondition, boolean hasEntityTarget, boolean structuralParent)
	{
		return decide(text, plan, hasCondition, hasEntityTarget, structuralParent)
			== Decision.ELIGIBLE;
	}

	static Decision decide(String text, StepLocationPlan plan,
		boolean hasCondition, boolean hasEntityTarget, boolean structuralParent)
	{
		if (plan == null || !plan.hasWaypoints()
			|| (plan.getReason() != LocationResolutionReason.RESOLVED
				&& plan.getReason() != LocationResolutionReason.MULTIPLE_DESTINATIONS))
		{
			return Decision.NO_RESOLVED_PLAN;
		}
		if (hasCondition)
		{
			return Decision.HAS_SEPARATE_CONDITION;
		}
		if (hasEntityTarget)
		{
			return Decision.ENTITY_INTERACTION;
		}
		if (structuralParent)
		{
			return Decision.STRUCTURAL_PARENT;
		}
		if (!isPureTravelText(text))
		{
			return Decision.NOT_PURE_TRAVEL;
		}
		StepLocationHint finalHint = plan.get(plan.size() - 1);
		if (finalHint == null || finalHint.isInferred())
		{
			return Decision.INFERRED_LOCATION;
		}
		LocationConfidence confidence = finalHint.getConfidence();
		if (confidence != LocationConfidence.EXACT
			&& confidence != LocationConfidence.HIGH
			&& confidence != LocationConfidence.MANUAL)
		{
			return Decision.UNTRUSTED_CONFIDENCE;
		}
		LocationSource source = finalHint.getSource();
		// TransportResolver itself requires travel context, so exact shorthand
		// such as "Dueling ring to Castle Wars" is safe. Manual/authored points
		// still require the parser to prove travel text; a pin alone is not proof.
		boolean trustedSource = source == LocationSource.TRANSPORT
			|| source == LocationSource.NAMED_PLACE
			|| source == LocationSource.AUTHORED_WAYPOINT
			|| source == LocationSource.USER_PIN;
		if (!trustedSource)
		{
			return Decision.UNTRUSTED_SOURCE;
		}
		return Decision.ELIGIBLE;
	}
}
