package com.hcimguide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;

/** Builds cached single- or multi-waypoint plans for every guide step. */
final class StepLocationPlanner
{
	/**
	 * Transports that deposit the player at a FIXED structure rather than at the
	 * destination itself, so arrival must be judged with a wider radius. A
	 * spirit tree lands you at the tree, a fairy ring at the ring, a glider at
	 * the glider pad - each can sit several tiles from the step objective.
	 */
	private static final java.util.regex.Pattern STRUCTURE_DROP_TRANSPORT =
		java.util.regex.Pattern.compile(
			"(?i)\\b(?:spirit tree|fairy ring|gnome glider|glider|minecart|"
			+ "quetzal|mushtree|spirit pool)\\b");

	static final int RESOLVER_VERSION = 6;
	/** Inherited-context steps beyond this run length are LOW confidence. */
	private static final int LOW_CONFIDENCE_CHAIN_LENGTH = 5;
	private static final Pattern WAYPOINT_BREAK = Pattern.compile(
		"(?i)\\s*(?:->|→|;|\\bthen\\b|\\bafter(?:wards| that)?\\b|\\bnext\\s+(?:go|head|run|walk|travel|teleport|take)\\b|\\bfollowed by\\b)\\s*");
	private static final Pattern RELATIVE_ONLY = Pattern.compile(
		"(?i)^\\s*(?:go|head|run|walk|climb|go back|return)\\s+(?:north|south|east|west|upstairs|downstairs|back|inside|outside)(?:[- ](?:east|west))?\\b.*");
	private static final Pattern DYNAMIC_NEAREST = Pattern.compile(
		"(?i)\\b(?:bank anywhere|nearest bank|any bank|nearest general store|teleport anywhere to bank)\\b");
	private static final Pattern QUEST_STAGE = Pattern.compile(
		"(?i)\\b(?:continue|complete|finish|start|begin)\\b[^.;]{0,70}"
			+ "\\b(?:quest|part\\s+(?:[ivx]+|[0-9]+))\\b");
	private static final Pattern GENERIC_PLACE = Pattern.compile(
		"(?i)\\b(?:the |a |an )?(?:bank|church|altar|pub|inn|mine|cave|dungeon|house|mansion|library|guild|quarry|portal|boat|minecart)\\b");
	private static final Pattern FAIRY_CODE_LIKE = Pattern.compile("(?i)\\b[a-d][i-l][p-s]\\b");

	private StepLocationPlanner()
	{
	}

	/** Legacy single-hint view retained for callers/tests that do not need waypoints. */
	static Map<String, StepLocationHint> build(Guide guide, Map<String, String> targets,
		PlaceDirectory places, TransportResolver transports,
		Function<String, WorldPoint> locationLookup)
	{
		Map<String, StepLocationPlan> plans = buildPlans(guide, targets, places, transports, locationLookup);
		Map<String, StepLocationHint> out = new HashMap<>();
		for (Map.Entry<String, StepLocationPlan> entry : plans.entrySet())
		{
			StepLocationPlan plan = entry.getValue();
			StepLocationHint hint = plan.get(plan.size() - 1);
			if (hint != null)
			{
				out.put(entry.getKey(), hint);
			}
		}
		return Collections.unmodifiableMap(out);
	}

	static Map<String, StepLocationPlan> buildPlans(Guide guide, Map<String, String> targets,
		PlaceDirectory places, TransportResolver transports,
		Function<String, WorldPoint> locationLookup)
	{
		if (guide == null || targets == null || places == null || transports == null
			|| locationLookup == null)
		{
			return Collections.emptyMap();
		}
		Map<String, StepLocationPlan> out = new HashMap<>();
		for (GuideEpisode episode : guide.getEpisodes())
		{
			for (GuideBank bank : episode.getBanks())
			{
				StepLocationHint context = null;
				int inheritedRun = 0;
				for (GuideStep step : bank.getSteps())
				{
					String text = step.getText() == null ? "" : step.getText();
					String target = targets.get(step.getKey());
					Resolution resolution = resolveDirect(text, target, places, transports, locationLookup);
					if (!resolution.waypoints.isEmpty())
					{
						StepLocationPlan plan = new StepLocationPlan(step.getKey(), resolution.waypoints,
							resolution.reason, resolution.candidates);
						out.put(step.getKey(), plan);
						context = resolution.waypoints.get(resolution.waypoints.size() - 1);
						inheritedRun = 0;
					}
					else if (resolution.breaksContext)
					{
						context = null;
						inheritedRun = 0;
						out.put(step.getKey(), new StepLocationPlan(step.getKey(), Collections.emptyList(),
							resolution.reason, resolution.candidates));
					}
					else if (context != null)
					{
						// the further an inherited destination is carried, the
						// less likely the player is still headed there - long
						// chains are honestly LOW confidence, which is what the
						// hide-low-confidence option and audit table act on
						inheritedRun++;
						StepLocationHint inherited = context.inferredCopy();
						if (inheritedRun > LOW_CONFIDENCE_CHAIN_LENGTH)
						{
							inherited = inherited.withSource(
								LocationSource.INHERITED_CONTEXT, LocationConfidence.LOW);
						}
						out.put(step.getKey(), new StepLocationPlan(step.getKey(),
							Collections.singletonList(inherited)));
					}
					else if (resolution.reason != LocationResolutionReason.NO_LOCATION_NEEDED)
					{
						out.put(step.getKey(), new StepLocationPlan(step.getKey(), Collections.emptyList(),
							resolution.reason, resolution.candidates));
					}
				}
			}
		}
		return Collections.unmodifiableMap(out);
	}


	/** Build one direct plan for a virtual clause without changing the guide model. */
	static StepLocationPlan buildPlanForText(String stepKey, String text, String target,
		PlaceDirectory places, TransportResolver transports,
		Function<String, WorldPoint> locationLookup)
	{
		if (stepKey == null || places == null || transports == null || locationLookup == null)
		{
			return new StepLocationPlan(stepKey, Collections.emptyList(),
				LocationResolutionReason.NO_LOCATION_NEEDED, Collections.emptyList());
		}
		Resolution resolution = resolveDirect(text == null ? "" : text, target,
			places, transports, locationLookup);
		return new StepLocationPlan(stepKey, resolution.waypoints,
			resolution.reason, resolution.candidates);
	}

	static boolean isQuestStageText(String text)
	{
		return text != null && QUEST_STAGE.matcher(text).find();
	}

	private static Resolution resolveDirect(String text, String target, PlaceDirectory places,
		TransportResolver transports, Function<String, WorldPoint> locationLookup)
	{
		List<StepLocationHint> waypoints = new ArrayList<>();
		List<String> unresolved = new ArrayList<>();
		String[] segments = WAYPOINT_BREAK.split(text, 12);
		boolean anyMovement = false;
		boolean unresolvedFinalMovement = false;
		for (int i = 0; i < segments.length; i++)
		{
			String segment = segments[i].trim();
			if (segment.isEmpty())
			{
				continue;
			}
			boolean movement = PlaceDirectory.hasMovementIntent(segment);
			boolean transportIntent = TransportResolver.hasTransportIntent(segment);
			StepLocationHint transport = transports.find(segment);

			// WAYPOINT_BREAK intentionally splits arrows and "then" into ordered
			// stops. Fairy-ring shorthand can place the access method in the prior
			// segment ("Ardy cloak -> CIS"), so retry only code-like segments with
			// their adjacent context. Restricting this fallback to fairy codes avoids
			// carrying an earlier jewelry teleport into a later ordinary waypoint.
			if (transport == null && i > 0 && FAIRY_CODE_LIKE.matcher(segment).find())
			{
				String withPreviousContext = segments[i - 1].trim() + " to " + segment;
				transport = transports.find(withPreviousContext);
				transportIntent |= transport != null
					|| TransportResolver.hasTransportIntent(withPreviousContext);
			}

			boolean structureDrop = STRUCTURE_DROP_TRANSPORT.matcher(segment).find()
				|| (transport != null && transport.getIdentity().startsWith("transport:fairy-ring:"));
			int transportRadius = structureDrop
				? StepLocationHint.STRUCTURE_TRANSPORT_RADIUS : 3;
			anyMovement |= movement || transportIntent;
			StepLocationHint place = places.find(segment);
			StepLocationHint chosen = null;
			// An exact structure beats an approximate place name. A fairy ring
			// has its own coordinates; a named place is a centroid. When a
			// segment carries both ("CKS Fairy Ring to Canifis") the ring is
			// where you actually arrive, so pointing at the middle of the town
			// was simply wrong - and it only showed up when returning to an
			// already-advanced step, because the forward pass had a live target.
			if (transport != null && structureDrop)
			{
				chosen = transport.asTransport(true, transportRadius);
			}
			else if (place != null && (movement || transportIntent))
			{
				chosen = transportIntent ? place.asTransport(true, transportRadius)
					: place.withSource(LocationSource.NAMED_PLACE, LocationConfidence.EXACT)
						.withPreferredOverEntity(true);
			}
			else if (transport != null)
			{
				// a DIRECT transport match ("Fairy Ring -> BKR") drops the player
				// at the structure too, so it needs the same widened arrival
				// radius as the named-place path above. Without this, auto-advance
				// failed here for exactly the reason spirit trees did.
				chosen = structureDrop
					? transport.asTransport(transport.isPreferredOverEntity(), transportRadius)
					: transport;
			}
			else if (place != null)
			{
				chosen = place.withSource(LocationSource.NAMED_PLACE, LocationConfidence.HIGH);
			}
			if (chosen != null)
			{
				addUnique(waypoints, chosen);
			}
			else if ((movement || transportIntent) && i == segments.length - 1)
			{
				unresolvedFinalMovement = true;
				unresolved.add(segment);
			}
		}

		if (target != null)
		{
			WorldPoint known = locationLookup.apply(target);
			if (known != null)
			{
				StepLocationHint entity = new StepLocationHint(target, known, false, false, false,
					"entity:" + Names.normalize(target), LocationSource.STORED_ENTITY,
					LocationConfidence.HIGH, 3, 2);
				if (waypoints.isEmpty() || !anyMovement)
				{
					addUnique(waypoints, entity);
				}
			}
			else if (waypoints.isEmpty())
			{
				return new Resolution(waypoints, LocationResolutionReason.ENTITY_LOCATION_UNKNOWN,
					Collections.singletonList(target), true);
			}
		}

		if (unresolvedFinalMovement)
		{
			// A known intermediate waypoint is still useful, but never inherit it into
			// later steps as though it were the unresolved final destination.
			return new Resolution(waypoints, LocationResolutionReason.UNKNOWN_NAMED_PLACE,
				unresolved, true);
		}
		if (!waypoints.isEmpty())
		{
			return new Resolution(waypoints,
				waypoints.size() > 1 ? LocationResolutionReason.MULTIPLE_DESTINATIONS
					: LocationResolutionReason.RESOLVED,
				unresolved, false);
		}
		if (DYNAMIC_NEAREST.matcher(text).find())
		{
			return new Resolution(waypoints, LocationResolutionReason.DYNAMIC_NEAREST_REQUIRED,
				Collections.singletonList(text), true);
		}
		if (RELATIVE_ONLY.matcher(text).find())
		{
			return new Resolution(waypoints, LocationResolutionReason.RELATIVE_DIRECTION_ONLY,
				Collections.singletonList(text), false);
		}
		if (TransportResolver.hasTransportIntent(text) && FAIRY_CODE_LIKE.matcher(text).find())
		{
			return new Resolution(waypoints, LocationResolutionReason.UNKNOWN_TRANSPORT_CODE,
				Collections.singletonList(text), true);
		}
		if (QUEST_STAGE.matcher(text).find())
		{
			return new Resolution(waypoints, LocationResolutionReason.QUEST_STAGE,
				Collections.singletonList(text), true);
		}
		if (PlaceDirectory.hasMovementIntent(text) && GENERIC_PLACE.matcher(text).find())
		{
			return new Resolution(waypoints, LocationResolutionReason.AMBIGUOUS_GENERIC_PLACE,
				Collections.singletonList(text), true);
		}
		return new Resolution(waypoints, LocationResolutionReason.NO_LOCATION_NEEDED,
			Collections.emptyList(), false);
	}

	private static void addUnique(List<StepLocationHint> waypoints, StepLocationHint candidate)
	{
		if (candidate == null || candidate.getPoint() == null)
		{
			return;
		}
		// Suppress only an immediately repeated resolver result. A route may
		// intentionally revisit the same destination (Wilderness -> Ardougne ->
		// Wilderness -> Ardougne); removing every earlier equivalent waypoint
		// destroys that ordered route.
		if (!waypoints.isEmpty()
			&& waypoints.get(waypoints.size() - 1).equivalentTo(candidate, 2))
		{
			return;
		}
		waypoints.add(candidate);
	}

	private static final class Resolution
	{
		private final List<StepLocationHint> waypoints;
		private final LocationResolutionReason reason;
		private final List<String> candidates;
		private final boolean breaksContext;

		private Resolution(List<StepLocationHint> waypoints, LocationResolutionReason reason,
			List<String> candidates, boolean breaksContext)
		{
			this.waypoints = waypoints;
			this.reason = reason;
			this.candidates = candidates;
			this.breaksContext = breaksContext;
		}
	}
}
