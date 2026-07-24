package com.hcimguide;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/** Builds a deterministic Markdown report for unresolved and low-confidence steps. */
final class LocationAudit
{
	/**
	 * Character budget per table. The summary counters above each table stay
	 * exact; only the rendered rows are capped, so a poorly-resolving
	 * 25,000-step guide cannot push a multi-megabyte document onto the
	 * clipboard. Checked by length rather than by counting rows so the guard is
	 * O(1) per append rather than rescanning the builder each time. Sized at
	 * roughly a thousand rows of typical width.
	 */
	private static final int MAX_TABLE_CHARS = 512 * 1024;

	private LocationAudit()
	{
	}

	static String toMarkdown(String guideId, Guide guide, Map<String, StepLocationPlan> plans)
	{
		Map<String, StepLocationPlan> safe = plans == null ? Collections.emptyMap() : plans;
		int total = 0;
		int resolved = 0;
		int inherited = 0;
		int manual = 0;
		int transport = 0;
		int entity = 0;
		int lowConfidence = 0;
		int unresolved = 0;
		StringBuilder unresolvedRows = new StringBuilder();
		StringBuilder lowRows = new StringBuilder();
		for (GuideEpisode episode : guide.getEpisodes())
		{
			for (GuideBank bank : episode.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					total++;
					StepLocationPlan plan = safe.get(step.getKey());
					if (plan != null && plan.hasWaypoints())
					{
						resolved++;
						for (StepLocationHint waypoint : plan.getWaypoints())
						{
							if (waypoint.getSource() == LocationSource.INHERITED_CONTEXT)
							{
								inherited++;
							}
							else if (waypoint.getSource() == LocationSource.USER_PIN)
							{
								manual++;
							}
							else if (waypoint.getSource() == LocationSource.TRANSPORT)
							{
								transport++;
							}
							else if (waypoint.getSource() == LocationSource.LIVE_NPC
								|| waypoint.getSource() == LocationSource.LIVE_OBJECT
								|| waypoint.getSource() == LocationSource.STORED_ENTITY)
							{
								entity++;
							}
							if (waypoint.getConfidence() == LocationConfidence.LOW)
							{
								lowConfidence++;
								appendResolvedRow(lowRows, episode, bank, step, waypoint);
							}
						}
						if (isPartiallyUnresolved(plan))
						{
							unresolved++;
							appendUnresolvedRow(unresolvedRows, episode, bank, step, plan);
						}
						continue;
					}
					LocationResolutionReason reason = plan == null
						? LocationResolutionReason.NO_LOCATION_NEEDED : plan.getReason();
					if (reason == LocationResolutionReason.NO_LOCATION_NEEDED)
					{
						continue;
					}
					unresolved++;
					appendUnresolvedRow(unresolvedRows, episode, bank, step, plan);
				}
			}
		}
		StringBuilder out = new StringBuilder();
		out.append("# Guide Overlay location-resolution audit\n\n")
			.append("- Guide: `").append(escape(guideId)).append("`\n")
			.append("- Resolver version: ").append(StepLocationPlanner.RESOLVER_VERSION).append("\n")
			.append("- Generated: ").append(Instant.now()).append("\n")
			.append("- Total steps: ").append(total).append("\n")
			.append("- Resolved steps: ").append(resolved).append("\n")
			.append("- Manual waypoints: ").append(manual).append("\n")
			.append("- Transport waypoints: ").append(transport).append("\n")
			.append("- Entity waypoints: ").append(entity).append("\n")
			.append("- Inherited waypoints: ").append(inherited).append("\n")
			.append("- Low-confidence waypoints: ").append(lowConfidence).append("\n")
			.append("- Unresolved actionable steps: ").append(unresolved).append("\n\n")
			.append("## Unresolved actionable steps\n\n")
			.append("| Episode | Episode title | Bank ID | Bank | Step key | Reason | Extracted candidate | Step text |\n")
			.append("|---:|---|---|---|---|---|---|---|\n")
			.append(unresolvedRows);
		if (unresolved == 0)
		{
			out.append("| — | — | — | — | — | — | — | No unresolved actionable locations |\n");
		}
		else if (unresolvedRows.length() >= MAX_TABLE_CHARS)
		{
			out.append("\n_Table truncated at the ").append(MAX_TABLE_CHARS / 1024)
				.append("KB safety cap; ").append(unresolved)
				.append(" unresolved steps were found in total._\n");
		}
		out.append("\n## Low-confidence resolved waypoints\n\n")
			.append("| Episode | Bank | Step key | Destination | Source | Confidence | Step text |\n")
			.append("|---:|---|---|---|---|---|---|\n")
			.append(lowRows);
		if (lowConfidence == 0)
		{
			out.append("| — | — | — | — | — | — | No low-confidence waypoints |\n");
		}
		else if (lowRows.length() >= MAX_TABLE_CHARS)
		{
			out.append("\n_Table truncated at the ").append(MAX_TABLE_CHARS / 1024)
				.append("KB safety cap; ").append(lowConfidence)
				.append(" low-confidence waypoints were found in total._\n");
		}
		return out.toString();
	}

	private static boolean isPartiallyUnresolved(StepLocationPlan plan)
	{
		if (plan == null || plan.getUnresolvedCandidates().isEmpty())
		{
			return false;
		}
		LocationResolutionReason reason = plan.getReason();
		return reason != LocationResolutionReason.RESOLVED
			&& reason != LocationResolutionReason.MULTIPLE_DESTINATIONS
			&& reason != LocationResolutionReason.NO_LOCATION_NEEDED;
	}

	private static void appendUnresolvedRow(StringBuilder rows, GuideEpisode episode, GuideBank bank,
		GuideStep step, StepLocationPlan plan)
	{
		if (rows.length() >= MAX_TABLE_CHARS)
		{
			return;
		}
		LocationResolutionReason reason = plan == null
			? LocationResolutionReason.NO_LOCATION_NEEDED : plan.getReason();
		rows.append("| ").append(episode.getNumber()).append(" | ")
			.append(escape(episode.getTitle())).append(" | ")
			.append(escape(bank.getId())).append(" | ")
			.append(escape(bank.getTitle())).append(" | `")
			.append(escape(step.getKey())).append("` | ")
			.append(reason.displayName()).append(" | ")
			.append(escape(plan == null ? "" : String.join("; ", plan.getUnresolvedCandidates())))
			.append(" | ").append(escape(step.getText())).append(" |\n");
	}

	private static void appendResolvedRow(StringBuilder rows, GuideEpisode episode, GuideBank bank,
		GuideStep step, StepLocationHint waypoint)
	{
		if (rows.length() >= MAX_TABLE_CHARS)
		{
			return;
		}
		rows.append("| ").append(episode.getNumber()).append(" | ")
			.append(escape(bank.getTitle())).append(" | `")
			.append(escape(step.getKey())).append("` | ")
			.append(escape(waypoint.getLabel())).append(" | ")
			.append(waypoint.getSource().displayName()).append(" | ")
			.append(waypoint.getConfidence().displayName()).append(" | ")
			.append(escape(step.getText())).append(" |\n");
	}

	private static String escape(String value)
	{
		return value == null ? "" : value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
	}
}
