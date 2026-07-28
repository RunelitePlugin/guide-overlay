package com.hcimguide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks every step of the loaded guide, resolves every item requirement against
 * the live client, and reports the ones that produce no item icon.
 *
 * <p>This exists because the offline item lists a developer can check against go
 * stale: they cannot contain items added after the list was captured. Only a
 * running client knows the full item set, so the authoritative answer to "which
 * requirements show as plain text" has to come from inside the game.</p>
 *
 * <p>The whole guide is walked in one pass, so nothing has to be scrolled
 * through by hand.</p>
 */
final class ItemAudit
{
	/** Bounds the report the same way the location audit bounds its own. */
	private static final int MAX_TABLE_CHARS = 512 * 1024;

	private ItemAudit()
	{
	}

	/**
	 * @param resolver live resolver, used only to ask whether a name has an icon
	 * @return a Markdown report listing every requirement with no item icon
	 */
	static String toMarkdown(String guideId, Guide guide, ItemIconResolver resolver,
		Map<String, List<ItemReq>> requirementsByStep)
	{
		if (guide == null)
		{
			return "# Item audit\n\nNo guide is loaded.\n";
		}

		// name -> where it was first seen, preserving guide order
		Map<String, String> unresolved = new LinkedHashMap<>();
		Map<String, String> concepts = new LinkedHashMap<>();
		Map<String, String> pending = new LinkedHashMap<>();
		int stepsWithItems = 0;
		int requirements = 0;
		int resolved = 0;
		// occurrence counters, so the summary adds up. The tables below list
		// DISTINCT names, which is a smaller number and a different unit -
		// mixing the two made 104 requirements look unaccounted for.
		int unresolvedHits = 0;
		int conceptHits = 0;
		int pendingHits = 0;

		for (GuideEpisode episode : guide.getEpisodes())
		{
			for (GuideBank bank : episode.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					List<ItemReq> reqs = requirementsByStep == null
						? StepItemRequirements.displayFor(step)
						: requirementsByStep.get(step.getKey());
					if (reqs == null || reqs.isEmpty())
					{
						continue;
					}
					stepsWithItems++;
					for (ItemReq req : reqs)
					{
						requirements++;
						String name = req.getName();
						String where = bank.getId() + " | " + step.getText();
						if (ConceptItems.isConcept(name))
						{
							conceptHits++;
							concepts.putIfAbsent(name, where);
							continue;
						}
						// stateOf(ItemReq) understands category and alternative
						// requirements; stateOf(String) flattens them to a
						// display name that may not be a real item at all
						ItemIconResolver.ResolutionState state = resolver.stateOf(req);
						if (state == ItemIconResolver.ResolutionState.RESOLVED)
						{
							resolved++;
						}
						else if (state == ItemIconResolver.ResolutionState.UNRESOLVABLE)
						{
							unresolvedHits++;
							unresolved.putIfAbsent(name, where);
						}
						else
						{
							// PENDING: the full-database scan had not reached this
							// name yet. Reported so the totals always add up -
							// silently skipping these hid 104 names on an earlier
							// run and made the guide look cleaner than it was.
							pendingHits++;
							pending.putIfAbsent(name, where);
						}
					}
				}
			}
		}

		StringBuilder out = new StringBuilder();
		out.append("# Item audit\n\n");
		out.append("Guide: ").append(guideId == null ? "(unknown)" : guideId).append("\n\n");
		out.append("Every requirement in the guide, checked against the live client.\n\n");
		out.append("Counts below are OCCURRENCES, so one item used in twenty steps counts\n");
		out.append("twenty times. The preload action reports UNIQUE names instead, which is\n");
		out.append("why its number is much smaller. Both are correct.\n\n");
		out.append("| | |\n|---|---:|\n");
		out.append("| Steps with items | ").append(stepsWithItems).append(" |\n");
		out.append("| Requirement occurrences | ").append(requirements).append(" |\n");
		out.append("| Resolved to an item | ").append(resolved).append(" |\n");
		out.append("| No item icon | ").append(unresolvedHits)
			.append(" (").append(unresolved.size()).append(" distinct) |\n");
		out.append("| Concept slots | ").append(conceptHits)
			.append(" (").append(concepts.size()).append(" distinct) |\n");
		out.append("| Still scanning | ").append(pendingHits)
			.append(" (").append(pending.size()).append(" distinct) |\n");
		out.append("| **Total** | **")
			.append(resolved + unresolvedHits + conceptHits + pendingHits)
			.append("** |\n\n");
		if (!pending.isEmpty())
		{
			out.append("## THIS REPORT IS INCOMPLETE\n\n");
			out.append(pendingHits).append(" of ").append(requirements);
			out.append(" requirements had not finished scanning when this ran, so the\n");
			out.append("\"No item icon\" count below is NOT the full picture. Wait a few\n");
			out.append("seconds and export again. Only trust a report where this section\n");
			out.append("is absent.\n\n");
		}

		out.append("Anything listed under \"No item icon\" showed as plain text rather\n");
		out.append("than an item picture. Each one needs either an alias to the real item\n");
		out.append("name, or removing if it is not an item at all.\n\n");

		appendTable(out, "No item icon", unresolved);
		appendTable(out, "Concept slots (expected, shown as an icon not an item)", concepts);
		appendTable(out, "Still scanning when the report ran", pending);

		if (out.length() > MAX_TABLE_CHARS)
		{
			out.setLength(MAX_TABLE_CHARS);
			out.append("\n\n(report truncated)\n");
		}
		return out.toString();
	}

	private static void appendTable(StringBuilder out, String title, Map<String, String> rows)
	{
		out.append("## ").append(title).append(" (").append(rows.size()).append(")\n\n");
		if (rows.isEmpty())
		{
			out.append("None.\n\n");
			return;
		}
		out.append("| Parsed name | First seen in |\n|---|---|\n");
		List<Map.Entry<String, String>> sorted = new ArrayList<>(rows.entrySet());
		sorted.sort(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
		for (Map.Entry<String, String> e : sorted)
		{
			out.append("| `").append(escape(e.getKey())).append("` | ")
				.append(escape(trim(e.getValue()))).append(" |\n");
		}
		out.append('\n');
	}

	private static String trim(String value)
	{
		return value.length() <= 110 ? value : value.substring(0, 110) + "...";
	}

	private static String escape(String value)
	{
		return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
	}
}
