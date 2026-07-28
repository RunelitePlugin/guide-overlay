package com.hcimguide;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;

/** Builds phase plans without inserting generated rows into guide banks. */
final class StepExecutionPlanner
{
	private static final Pattern ITEM_SUFFIX = Pattern.compile("(?is)\\(\\s*items?\\s*:\\s*.*?\\)\\s*$");

	private StepExecutionPlanner()
	{
	}

	static Map<String, StepExecutionPlan> buildPlans(Guide guide,
		PlaceDirectory places, TransportResolver transports,
		Function<String, WorldPoint> locationLookup,
		Map<String, StepLocationPlan> parentPlans)
	{
		if (guide == null || places == null || transports == null || locationLookup == null)
		{
			return Collections.emptyMap();
		}
		Map<String, StepExecutionPlan> out = new HashMap<>();
		for (GuideEpisode episode : guide.getEpisodes())
		{
			for (GuideBank bank : episode.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					StepLocationPlan parent = parentPlans == null ? null : parentPlans.get(step.getKey());
					StepExecutionPlan plan = build(step, places, transports, locationLookup, parent);
					if (plan != null)
					{
						out.put(step.getKey(), plan);
					}
				}
			}
		}
		return Collections.unmodifiableMap(out);
	}

	static StepExecutionPlan build(GuideStep step, PlaceDirectory places,
		TransportResolver transports, Function<String, WorldPoint> locationLookup)
	{
		return build(step, places, transports, locationLookup, null);
	}

	static StepExecutionPlan build(GuideStep step, PlaceDirectory places,
		TransportResolver transports, Function<String, WorldPoint> locationLookup,
		StepLocationPlan parentPlan)
	{
		if (step == null)
		{
			return null;
		}
		StepPhaseParser.Parsed parsed = StepPhaseParser.parse(step.getText());
		if (parsed.getMode() == StepPhaseParser.Mode.MANUAL)
		{
			return null;
		}
		// A guidance-only plan exists to TARGET, never to complete. The shape
		// gates below (no compound, travel-first, task-last) exist to make
		// arrival completion safe; they are not needed when completion is
		// impossible by construction. Without this exemption GUIDANCE_ONLY was
		// unreachable - the parser produced it and the planner rejected exactly
		// those shapes, so no plan was ever built.
		final boolean guidanceOnly =
			parsed.getMode() == StepPhaseParser.Mode.GUIDANCE_ONLY;
		int travelClauses = 0;
		for (StepPhaseParser.Clause clause : parsed.getClauses())
		{
			if (clause.getKind() == StepPhaseParser.Kind.TRAVEL)
			{
				travelClauses++;
			}
		}

		List<StepExecutionPhase> phases = new ArrayList<>();
		Map<String, Integer> idOccurrences = new HashMap<>();
		StepCondition parentCondition = ConditionParser.parse(step.getText());
		boolean parentConditionRepresented = parentCondition == null;
		if (parsed.hasItemPreparation())
		{
			StepCondition condition = ConditionParser.parse(step.getText());
			if (condition == null || condition.getType() != StepCondition.Type.ITEMS_IN_INVENTORY)
			{
				return null; // explicit prep text that cannot be proved: fail closed
			}
			phases.add(new StepExecutionPhase(uniqueId(step.getKey(), "prep", "items", idOccurrences),
				"Prepare listed items", StepPhaseParser.Kind.PREPARATION,
				null, null, condition));
			parentConditionRepresented |= conditionsEquivalent(parentCondition, condition);
		}

		for (StepPhaseParser.Clause clause : parsed.getClauses())
		{
			if (clause.getKind() == StepPhaseParser.Kind.NOTE)
			{
				continue;
			}
			if (clause.getKind() == StepPhaseParser.Kind.COMPOUND && !guidanceOnly)
			{
				return null;
			}
			String target = TargetExtractor.extract(clause.getText());
			StepCondition condition = ConditionParser.parse(clause.getText());
			parentConditionRepresented |= conditionsEquivalent(parentCondition, condition);
			StepLocationPlan location = StepLocationPlanner.buildPlanForText(
				step.getKey(), clause.getText(), target, places, transports, locationLookup);
			if (clause.getKind() == StepPhaseParser.Kind.TRAVEL)
			{
				if (!usableLocation(location) && travelClauses == 1
					&& trustedParentLocation(parentPlan))
				{
					// Grammar such as "Teleport & bank at Camelot" puts the
					// destination in the following task clause. The legacy full-row
					// planner can still prove one direct destination; with exactly one
					// travel phase it is safe to lend that plan to the travel phase.
					location = parentPlan;
				}
				if (!usableLocation(location))
				{
					return null; // a travel phase without a proven destination is not executable
				}
			}
			phases.add(new StepExecutionPhase(
				uniqueId(step.getKey(), clause.getKind().name().toLowerCase(),
					clause.getText(), idOccurrences),
				clause.getText(), clause.getKind(), location, target, condition));
		}

		if (phases.isEmpty() || !parentConditionRepresented)
		{
			// The legacy row has a game-state condition that no generated phase owns.
			// Virtualizing it would hide that condition from activeStepCondition(), so
			// leave the parent on the proven legacy path instead.
			return null;
		}
		// Do not create a phase machine that starts on an unverifiable manual task.
		int firstNonPrep = 0;
		while (firstNonPrep < phases.size()
			&& phases.get(firstNonPrep).getKind() == StepPhaseParser.Kind.PREPARATION)
		{
			firstNonPrep++;
		}
		if (!guidanceOnly
			&& (firstNonPrep >= phases.size() || !phases.get(firstNonPrep).isTravel()))
		{
			return null;
		}
		if (guidanceOnly && phases.isEmpty())
		{
			return null;
		}
		// A task may only be the final phase. Otherwise there is no safe automatic
		// signal for moving past it to later travel.
		if (!guidanceOnly)
		{
			for (int i = firstNonPrep; i + 1 < phases.size(); i++)
			{
				if (phases.get(i).getKind() == StepPhaseParser.Kind.TASK)
				{
					return null;
				}
			}
		}
		return new StepExecutionPlan(step.getKey(), parsed.getMode(), phases);
	}

	private static boolean usableLocation(StepLocationPlan plan)
	{
		return plan != null && plan.hasWaypoints()
			&& (plan.getReason() == LocationResolutionReason.RESOLVED
				|| plan.getReason() == LocationResolutionReason.MULTIPLE_DESTINATIONS);
	}

	private static boolean trustedParentLocation(StepLocationPlan plan)
	{
		if (!usableLocation(plan))
		{
			return false;
		}
		StepLocationHint last = plan.get(plan.size() - 1);
		if (last == null || last.isInferred())
		{
			return false;
		}
		LocationConfidence confidence = last.getConfidence();
		return confidence == LocationConfidence.EXACT
			|| confidence == LocationConfidence.HIGH
			|| confidence == LocationConfidence.MANUAL;
	}

	private static boolean conditionsEquivalent(StepCondition left, StepCondition right)
	{
		if (left == right)
		{
			return true;
		}
		if (left == null || right == null || left.getType() != right.getType())
		{
			return false;
		}
		switch (left.getType())
		{
			case QUEST_STARTED:
			case QUEST_FINISHED:
				return left.getQuest() == right.getQuest();
			case SKILL_LEVEL:
				return left.getSkill() == right.getSkill() && left.getLevel() == right.getLevel();
			case ITEMS_IN_INVENTORY:
				return itemRequirementsEquivalent(left.getItems(), right.getItems());
			default:
				return false;
		}
	}

	private static boolean itemRequirementsEquivalent(List<ItemReq> left, List<ItemReq> right)
	{
		if (left == right)
		{
			return true;
		}
		if (left == null || right == null || left.size() != right.size())
		{
			return false;
		}
		for (int i = 0; i < left.size(); i++)
		{
			ItemReq a = left.get(i);
			ItemReq b = right.get(i);
			if (a.getQuantity() != b.getQuantity()
				|| a.getCompletionQuantity() != b.getCompletionQuantity()
				|| a.isInventoryFill() != b.isInventoryFill()
				|| a.isAlternativeGroup() != b.isAlternativeGroup()
				|| a.isCategoryRequirement() != b.isCategoryRequirement()
				|| !Names.normalize(a.getName()).equals(Names.normalize(b.getName()))
				|| !a.getAlternatives().equals(b.getAlternatives())
				|| a.getCategory() != b.getCategory())
			{
				return false;
			}
		}
		return true;
	}

	private static String uniqueId(String parentKey, String kind, String text,
		Map<String, Integer> occurrences)
	{
		String base = id(parentKey, kind, text);
		int occurrence = occurrences.merge(base, 1, Integer::sum);
		return occurrence == 1 ? base : base + ":" + occurrence;
	}

	private static String id(String parentKey, String kind, String text)
	{
		String normalized = Names.normalize(ITEM_SUFFIX.matcher(text == null ? "" : text)
			.replaceAll(""));
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(normalized.getBytes(StandardCharsets.UTF_8));
			StringBuilder hash = new StringBuilder();
			for (int i = 0; i < 6; i++)
			{
				hash.append(String.format("%02x", digest[i]));
			}
			return parentKey + "@" + kind + ":" + hash;
		}
		catch (Exception ignored)
		{
			return parentKey + "@" + kind + ":" + Integer.toHexString(normalized.hashCode());
		}
	}
}
