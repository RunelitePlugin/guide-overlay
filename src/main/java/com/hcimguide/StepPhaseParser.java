package com.hcimguide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative clause splitter and classifier for compound guide rows.
 *
 * <p>This parser never creates persisted checklist rows. It only describes the
 * ordered runtime actions inside one source {@link GuideStep}. Ambiguous text
 * fails closed to MANUAL rather than manufacturing arrival semantics.</p>
 */
final class StepPhaseParser
{
	enum Kind
	{
		TRAVEL,
		TASK,
		NOTE,
		PREPARATION,
		COMPOUND
	}

	enum Mode
	{
		PURE_TRAVEL,
		TRAVEL_THEN_TASK,
		/**
		 * Clauses parsed cleanly and are useful for TARGETING, but arrival can
		 * never complete the parent. A single task clause such as
		 * "Use Star on Experiment Entrance" has no travel at all, yet the phase
		 * carries the object so highlighting works on the clause rather than the
		 * whole row. Completion stays exactly as manual as MANUAL.
		 */
		GUIDANCE_ONLY,
		MANUAL
	}

	static final class Clause
	{
		private final Kind kind;
		private final String text;

		Clause(Kind kind, String text)
		{
			this.kind = kind;
			this.text = text;
		}

		Kind getKind()
		{
			return kind;
		}

		String getText()
		{
			return text;
		}
	}

	static final class Parsed
	{
		private final List<Clause> clauses;
		private final Mode mode;
		private final boolean itemPreparation;
		private final boolean requiredParentheticalAction;

		Parsed(List<Clause> clauses, Mode mode, boolean itemPreparation,
			boolean requiredParentheticalAction)
		{
			this.clauses = Collections.unmodifiableList(new ArrayList<>(clauses));
			this.mode = mode;
			this.itemPreparation = itemPreparation;
			this.requiredParentheticalAction = requiredParentheticalAction;
		}

		List<Clause> getClauses()
		{
			return clauses;
		}

		Mode getMode()
		{
			return mode;
		}

		boolean hasItemPreparation()
		{
			return itemPreparation;
		}

		boolean hasRequiredParentheticalAction()
		{
			return requiredParentheticalAction;
		}
	}

	private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[(?:[^|\\]]*\\|)?([^\\]]+)\\]\\]");
	private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]+\\]");
	private static final Pattern ITEM_SUFFIX = Pattern.compile("(?is)\\(\\s*items?\\s*:\\s*.*?\\)\\s*$");
	private static final Pattern OPTIONAL = Pattern.compile(
		"(?i)\\b(?:optional(?:ly)?|if\\s+you\\s+(?:want|wish|prefer)|at\\s+(?:your|a)\\s+convenience|"
			+ "when(?:ever)?\\s+needed|as\\s+needed|consider)\\b");
	private static final Pattern ADVISORY_LEAD = Pattern.compile(
		"(?i)^\\s*(?:optional(?:ly)?|note|alternatively|whenever|at\\s+your\\s+convenience|"
			+ "you\\s+(?:can|may|might|should|will|probably|could)|consider|remember|"
			+ "the\\s+fastest\\s+method|it\\s+is\\s+(?:recommended|best)|this\\s+(?:has|means|bottle)|"
			+ "there\\s+(?:is|was)|for\\s+(?:using|an\\s+example)|right\\s+now\\s+that)\\b");

	private static final String TRANSPORT_OBJECT =
		"(?:boat|ship|portal|minecart|glider|quetzal|canoe|charter|ferry|rowboat|raft|"
			+ "spirit\\s+tree|fairy\\s+ring|lever|hole|trapdoor|ladder|stairs?|rope\\s+swing|"
			+ "magic\\s+carpet|travel\\s+cart|lady\\s+of\\s+the\\s+waves)";
	private static final Pattern TAKE_TRANSPORT = Pattern.compile(
		"(?i)\\btake\\s+(?:(?:the|a|an|north)\\s+)?"
			+ "(?:(?:[a-z][a-z'’?-]*|of)\\s+){0,4}"
			+ TRANSPORT_OBJECT + "\\b|\\btake\\s+to\\s+\\S");
	private static final Pattern USE_TRANSPORT = Pattern.compile(
		"(?i)^\\s*use\\s+(?:(?:the|a|an)\\s+|your\\s+(?:freshly\\s+charged\\s+)?|"
			+ "freshly\\s+charged\\s+|\\d+\\s+)?"
			+ "(?:ring|amulet|amulet\\s+of\\s+glory|glory|necklace|bracelet|cloak|ectophial|chronicle|"
			+ "teleport\\s+crystal|digsite\\s+pendant|camulet|fairy\\s+ring|house\\s+teleport|"
			+ "games\\s+necklace|pharaoh.?s\\s+sceptre|rope|ectotokens?)"
			+ "[^.;]{0,100}\\b(?:teleport|return|get\\s+there|go\\s+to|enter|area)\\b");
	private static final Pattern TRANSPORT_SHORTHAND = Pattern.compile(
		"(?i)^\\s*(?:(?:dueling|duelling)\\s+ring|ring\\s+of\\s+dueling|games?\\s+necklace|necklace\\s+of\\s+passage|"
			+ "chronicle|ectophial|camulet|teleport\\s+crystal|pharaoh.?s\\s+sceptre|"
			+ "(?:ardy|ardougne)\\s+cloak|rada.?s\\s+blessing|blessing|house\\s+teleport|"
			+ "home\\s+teleport|minigame\\s+teleport|fairy\\s+ring|spirit\\s+tree)"
			+ "(?:\\s+teleport)?\\s*(?:to|->|→)\\s*\\S");
	private static final Pattern NAMED_TELEPORT_HEAD = Pattern.compile(
		"(?i)^\\s*(?:(?:[a-z][a-z'’?-]*|of|the)\\s+){1,6}teleport"
			+ "(?:\\s+(?:to|towards?|into|outside))?\\b");
	private static final Pattern RETURN_ITEM = Pattern.compile(
		"(?i)^\\s*return\\s+(?:the|a|an|your|this|that)\\s+\\S");
	private static final Pattern BARE_TRANSPORT_HEAD = Pattern.compile(
		"(?i)^\\s*(?:boat|ship|portal|minecart|glider|quetzal|canoe|ferry|rowboat|raft|"
			+ "spirit\\s+tree|fairy\\s+ring)\\s+(?:to|towards?|into|back)\\b");
	private static final Pattern TRANSPORT_ARROW_HEAD = Pattern.compile(
		"(?i)^\\s*(?:boat|ship|portal|minecart|glider|quetzal|canoe|ferry|rowboat|raft|"
			+ "spirit\\s+tree|fairy\\s+ring)\\s*(?:->|→)\\s*\\S");
	private static final Pattern FAIRY_CODE_ONLY = Pattern.compile("(?i)^\\s*[a-d][i-l][p-s]\\s*$");
	private static final Pattern FAIRY_RING_TO_FINAL_DESTINATION = Pattern.compile(
		"(?i)(\\b(?:[a-d][i-l][p-s]\\s+fairy\\s+ring|fairy\\s+ring\\s+[a-d][i-l][p-s])\\b)"
			+ "\\s+to\\s+get\\s+to\\s+(?=\\S)");
	private static final Pattern ACCESS_METHOD_ONLY = Pattern.compile(
		"(?i)^\\s*(?:(?:ardy|ardougne)\\s+(?:cape|cloak)(?:\\s+teleport)?(?:\\s+\\d+)?|"
			+ "ardougne\\s+cape\\s+teleport|fairy\\s+ring|minecart|glider|quetzal|boat|ship|"
			+ "teleport\\s+to\\s+poh|teleport\\s+poh|poh|house\\s+teleport|blessing)\\s*$");
	private static final Pattern CONTEXTUAL_TRANSPORT_HEAD = Pattern.compile(
		"(?i)^\\s*[^:]{1,40}:\\s*(?:(?:ardy|ardougne)\\s+(?:cape|cloak)|teleport|"
			+ "fairy\\s+ring|spirit\\s+tree)\\b");
	private static final Pattern ALTERNATIVE_ROUTE = Pattern.compile(
		"(?i)\\b(?:either|otherwise|instead|else)\\b|\\b(?:teleport|travel|go|head|run|walk|"
			+ "charter|take|use)[^.;]{0,90}\\bor\\b[^.;]{0,90}"
			+ "(?:teleport|travel|go|head|run|walk|charter|take|use)\\b");
	private static final Pattern QUANTIFIED_ROUTE = Pattern.compile(
		"(?i)\\b(?:(?:all|each|the\\s+following)\\s+)?(?:\\d+|five|four|three|two)\\s+"
			+ "(?:cities|locations|places|teleports?|altars?)\\b");
	private static final Pattern SPELLBOOK_ACTION = Pattern.compile(
		"(?i)^(?:(?:lunar|regular|ancient|arceuus)\\s+spellbook|"
			+ ".*\\b(?:change|switch|swap|return)\\b.*\\bspellbook)\\s*$");
	private static final Pattern NAMED_TRANSPORT_DESTINATION = Pattern.compile(
		"(?i)^\\s*(?:[a-z][a-z'’?-]*\\s+){1,5}(?:portal|boat|minecart|glider|ferry)\\s*$");
	private static final Pattern DESTINATION_BANK = Pattern.compile(
		"(?i)\\b(?:to|towards?|at|near|outside|inside|north\\s+of|south\\s+of|east\\s+of|west\\s+of)"
			+ "\\s+(?:the\\s+)?(?:[a-z][a-z'’?-]*\\s+){0,5}bank\\b");
	private static final Pattern INLINE_ITEM_LOADOUT = Pattern.compile(
		"(?i)\\bwith\\s+(?:at\\s+least\\s+)?(?:\\d+|one|two|three|four|five|some|your)\\b"
			+ "[^.;]{0,160}\\b(?:items?|runes?|orbs?|logs?|food|potions?|bars?|nails?|essence|gear)\\b");

	private static final Pattern TRAVEL_HEAD = Pattern.compile(
		"(?i)^\\s*(?:(?:first|now|next|then|finally|quickly|immediately)\\s+)*"
			+ "(?:make\\s+your\\s+way|go(?:\\s+back)?|head(?:\\s+back|\\s+over)?|run|walk|travel|"
			+ "return(?:\\s+back)?\\s+(?:to|home|via)|visit|"
			+ "enter|re-?enter|leave|exit|cross|follow|squeeze|climb|descend|ascend|board|ride|sail|charter|"
			+ "tele(?:port)?(?:ed|ing|s)?|home\\s+tele(?:port)?|minigame\\s+tele(?:port)?|"
			+ "fairy\\s+ring|spirit\\s+tree|(?:ardy|ardougne)\\s+cloak|house\\s+teleport|"
			+ "[a-d][i-l][p-s]\\s+fairy\\s+ring)\\b");

	private static final Pattern TASK_HEAD = Pattern.compile(
		"(?i)^\\s*(?:(?:first|now|next|then|finally|quickly|immediately)\\s+)*"
			+ "(?:talk|speak|complete|start|begin|continue|finish|kill|defeat|fight|attack|buy|purchase|collect|grab|"
			+ "pickpocket|mine|chop|cut|craft|make(?!\\s+your\\s+way)|smelt|cook|withdraw|deposit|bank|rebank|"
			+ "pray|train|obtain|deliver|give|search|loot|plant|harvest|catch|fish|thieve|steal|build|repair|"
			+ "solve|light|burn|bury|equip|wear|wield|pick|drop|progress|resume|check|read|dig|pay|trade|sell|"
			+ "exchange|fill|empty|drink|eat|cast|enchant|charge|recharge|unlock|open|inspect|pull|push|turn|"
			+ "place|put|hand|show|ask|accept|claim|activate|restock|restore|world\\s+hop|hop|move|imbue|smith|"
			+ "create|learn|look|peek|peak|chart|dock|recover|recruit|meet|find|identify|do|participate|stay|wait|logout|"
			+ "log\\s+back|switch|swap|change|right\\s*click|choose|chose|select|send|dump|dye|fix|proceed|"
			+ "attach|alch|prepare|break|get(?!\\s+to\\b)|cash|take|use)\\b");

	private static final Pattern TASK_ANY = Pattern.compile(
		"(?i)\\b(?:talk|speak|complete|start|begin|continue|finish|kill|defeat|fight|attack|buy|purchase|collect|grab|"
			+ "pickpocket|mine|chop|cut|craft|make(?!\\s+your\\s+way)|smelt|cook|withdraw|deposit|bank|rebank|"
			+ "pray|train|obtain|deliver|give|search|loot|plant|harvest|catch|fish|thieve|steal|build|repair|"
			+ "solve|light|burn|bury|equip|wear|wield|pick|drop|progress|resume|check|read|dig|pay|trade|sell|"
			+ "exchange|fill|empty|drink|eat|cast|enchant|charge|recharge|unlock|open|inspect|pull|push|turn|"
			+ "place|put|hand|show|ask|accept|claim|activate|restock|restore|world\\s+hop|imbue|smith|create|"
			+ "learn|look\\s+at|peek|chart|dock|recover|recruit|meet|find|identify|participate|stay|wait|logout|"
			+ "log\\s+back|switch|swap(?:ping|ped|s)?|change|right\\s*click|choose|chose|select|send|"
			+ "dump|dye|fix|proceed|attach(?:ing|ed|es)?|alch(?:ing|ed|es)?|prepare|break|get(?!\\s+to\\b)|cash|"
			+ "visiting|use)\\b");

	private static final Pattern ACTION_HEAD = Pattern.compile(
		"(?i)^\\s*(?:then|next|afterwards|after\\s+that|quickly|immediately|now)?\\s*"
			+ "(?:make\\s+your\\s+way|go|head|run|walk|travel|return|visit|enter|re-?enter|leave|exit|cross|"
			+ "follow|climb|descend|ascend|board|ride|sail|charter|tele(?:port)?|home\\s+tele(?:port)?|"
			+ "minigame\\s+tele(?:port)?|fairy\\s+ring|spirit\\s+tree|(?:ardy|ardougne)\\s+cloak|take|use|"
			+ "talk|speak|complete|start|begin|continue|finish|kill|defeat|fight|attack|buy|purchase|collect|grab|"
			+ "pickpocket|mine|chop|cut|craft|make|smelt|cook|withdraw|deposit|bank|rebank|pray|train|obtain|"
			+ "deliver|give|search|loot|plant|harvest|catch|fish|thieve|steal|build|repair|solve|light|burn|"
			+ "bury|equip|wear|wield|pick|drop|progress|resume|check|read|dig|pay|trade|sell|exchange|fill|"
			+ "empty|drink|eat|cast|enchant|charge|recharge|unlock|open|inspect|pull|push|turn|place|put|hand|"
			+ "show|ask|accept|claim|activate|restock|restore|world\\s+hop|hop|move|imbue|smith|create|learn|"
			+ "look|peek|peak|chart|dock|recover|recruit|meet|find|identify|do|participate|stay|wait|logout|log\\s+back|"
			+ "switch|swap|change|right\\s*click|choose|chose|select|send|dump|dye|fix|proceed|attach|alch|"
			+ "prepare|break|get(?!\\s+to\\b)|cash)\\b");

	private static final Pattern INFORMATIONAL = Pattern.compile(
		"(?i)^\\s*(?:do\\s+not|don['’]?t|nothing|this\\s+bottle\\s+will|the\\s+bottle|there\\s+(?:is|was)|"
			+ "all\\s+lamps|one\\s+or\\s+two|ogres\\b|basilisks\\b|follow\\s+this\\s+guide\\b)");
	private static final Pattern LEAVE_LOCATION = Pattern.compile(
		"(?i)^\\s*leave\\s+(?:the\\s+)?(?:(?:[a-z][a-z'’?-]*|of)\\s+){0,5}"
			+ "(?:cave|room|dungeon|building|area|portal|altar|island|ship|boat|bank|guild|"
			+ "house|castle|tower|mine|lair|tunnel|entrance|exit)\\b");
	private static final Pattern LEAVE_ITEM = Pattern.compile(
		"(?i)^\\s*leave\\s+(?:one|two|three|four|five|six|seven|eight|nine|ten|\\d+|your|the|a|an)\\s+"
			+ "(?!(?:cave|room|dungeon|building|area|portal|altar|island|ship|boat|bank|guild|"
			+ "house|castle|tower|mine|lair|tunnel|entrance|exit)\\b)");
	private static final Pattern TAKE_ITEM = Pattern.compile(
		"(?i)\\btake\\s+(?:one|two|three|four|five|six|seven|eight|nine|ten|\\d+|your|the|a|an)\\s+"
			+ "(?!(?:boat|ship|portal|minecart|glider|quetzal|canoe|charter|ferry|rowboat|raft|spirit\\s+tree|fairy\\s+ring)\\b)");

	private StepPhaseParser()
	{
	}

	static Parsed parse(String value)
	{
		String text = normalizeExplicitFinalDestination(clean(value));
		List<String> rawClauses = mergeAccessCodeClauses(splitClauses(text));
		List<Clause> clauses = new ArrayList<>();
		Kind previousAction = null;
		boolean riskyIgnoredText = false;
		boolean sawAction = false;
		for (String raw : rawClauses)
		{
			String clause = raw.trim();
			if (clause.isEmpty())
			{
				continue;
			}
			Kind kind = classify(clause);
			// A leading "Visit Canifis" is satisfied by reaching the named place.
			// After an earlier travel leg, however, "and visit the museum" is a
			// separate objective rather than proof that the first arrival finished it.
			if (previousAction == Kind.TRAVEL && kind == Kind.TRAVEL
				&& stripLeadingContext(BRACKET_TAG.matcher(clause).replaceAll("").trim())
					.matches("(?i)^visit\\b.*"))
			{
				kind = Kind.TASK;
			}
			boolean inheritedTravel = false;
			if (kind == Kind.NOTE && previousAction == Kind.TRAVEL
				&& !isBenignTravelContext(clause) && isRouteFragment(clause))
			{
				kind = Kind.TRAVEL;
				inheritedTravel = true;
			}
			if (kind == Kind.NOTE && ((!sawAction && !isBenignTravelContext(clause))
				|| (sawAction && !isHarmlessIgnoredClause(clause)
					&& !isBenignTravelContext(clause))))
			{
				riskyIgnoredText = true;
			}
			clauses.add(new Clause(kind, inheritedTravel
				? normalizeEllipticalText(clause, previousAction) : trimBoundary(clause)));
			if (kind != Kind.NOTE)
			{
				sawAction = true;
				previousAction = kind;
			}
		}

		ParentheticalScan parenthetical = scanParentheticals(text);
		List<Clause> actionable = new ArrayList<>();
		if (parenthetical.itemPreparation)
		{
			actionable.add(new Clause(Kind.PREPARATION, "Items requirement"));
		}
		for (Clause clause : clauses)
		{
			if (clause.kind != Kind.NOTE)
			{
				actionable.add(clause);
			}
		}

		Mode mode = Mode.MANUAL;
		if (!parenthetical.requiredAction && !riskyIgnoredText && !actionable.isEmpty())
		{
			boolean compound = false;
			List<Clause> nonPreparation = new ArrayList<>();
			for (Clause clause : actionable)
			{
				compound |= clause.kind == Kind.COMPOUND;
				if (clause.kind != Kind.PREPARATION)
				{
					nonPreparation.add(clause);
				}
			}
			if (!compound && !nonPreparation.isEmpty()
				&& nonPreparation.get(0).kind == Kind.TRAVEL)
			{
				boolean taskBeforeLast = false;
				for (int i = 0; i + 1 < nonPreparation.size(); i++)
				{
					if (nonPreparation.get(i).kind == Kind.TASK)
					{
						taskBeforeLast = true;
						break;
					}
				}
				if (!taskBeforeLast)
				{
					mode = nonPreparation.get(nonPreparation.size() - 1).kind == Kind.TASK
						? Mode.TRAVEL_THEN_TASK : Mode.PURE_TRAVEL;
				}
			}

			// ---- guidance-only fallbacks ----------------------------------
			// Reviewed against all 152 manual rows in the two bundled guides.
			// These produce phases for TARGETING only. Arrival completion is
			// decided separately and is unchanged, so no row that should stay
			// manual becomes auto-completable.
			if (mode == Mode.MANUAL)
			{
				boolean anyTravel = false;
				boolean anyResolvableTask = false;
				for (Clause clause : nonPreparation)
				{
					anyTravel |= clause.kind == Kind.TRAVEL;
					anyResolvableTask |= clause.kind == Kind.TASK;
				}
				// Rule 1: a LEADING task must not reject the plan. Travel after a
				//         task still guides ("Break another POH tab, return to Thurgo").
				// Rule 2: a lone task is a valid one-phase guidance plan
				//         ("Use Star on Experiment Entrance").
				// Rule 3: a COMPOUND next to a resolvable TRAVEL keeps the travel
				//         phase instead of discarding the whole row.
				// Rule 4: NOTE clauses between travel legs must not break the chain;
				//         notes are already excluded from nonPreparation.
				if (anyTravel || anyResolvableTask)
				{
					mode = Mode.GUIDANCE_ONLY;
				}
			}
		}
		return new Parsed(clauses, mode, parenthetical.itemPreparation,
			parenthetical.requiredAction);
	}

	private static Kind classify(String clause)
	{
		String text = stripLeadingContext(BRACKET_TAG.matcher(clause).replaceAll("").trim());
		if (text.isEmpty() || INFORMATIONAL.matcher(text).find()
			|| ADVISORY_LEAD.matcher(text).find())
		{
			return Kind.NOTE;
		}
		if (!LEAVE_LOCATION.matcher(text).find() && LEAVE_ITEM.matcher(text).find())
		{
			return Kind.TASK;
		}
		if (RETURN_ITEM.matcher(text).find())
		{
			return Kind.TASK;
		}
		if (SPELLBOOK_ACTION.matcher(text).find())
		{
			return Kind.TASK;
		}
		if (ALTERNATIVE_ROUTE.matcher(text).find() || QUANTIFIED_ROUTE.matcher(text).find()
			|| INLINE_ITEM_LOADOUT.matcher(text).find())
		{
			return Kind.COMPOUND;
		}

		boolean takeTransport = TAKE_TRANSPORT.matcher(text).find();
		boolean useTransport = USE_TRANSPORT.matcher(text).find();
		boolean travel = TRAVEL_HEAD.matcher(text).find() || takeTransport || useTransport
			|| TRANSPORT_SHORTHAND.matcher(text).find()
			|| NAMED_TELEPORT_HEAD.matcher(text).find()
			|| BARE_TRANSPORT_HEAD.matcher(text).find()
			|| TRANSPORT_ARROW_HEAD.matcher(text).find()
			|| NAMED_TRANSPORT_DESTINATION.matcher(text).find()
			|| CONTEXTUAL_TRANSPORT_HEAD.matcher(text).find()
			|| Pattern.compile("(?i)^\\s*(?:[a-d][i-l][p-s]\\s+fairy\\s+ring|"
				+ "(?:ardy|ardougne)\\s+cloak\\s*(?:->|→)|blessing\\s*(?:->|→)|spirit\\s+tree\\b)")
				.matcher(text).find();
		boolean task = TASK_HEAD.matcher(text).find();

		String withoutTransport = stripOptionalParentheticals(text);
		withoutTransport = TAKE_TRANSPORT.matcher(withoutTransport).replaceAll("");
		if (useTransport)
		{
			withoutTransport = USE_TRANSPORT.matcher(withoutTransport).replaceAll("");
		}
		withoutTransport = DESTINATION_BANK.matcher(withoutTransport).replaceAll("");
		boolean requiredTask = TASK_ANY.matcher(withoutTransport).find()
			|| TAKE_ITEM.matcher(withoutTransport).find();
		if (travel)
		{
			return requiredTask ? Kind.COMPOUND : Kind.TRAVEL;
		}
		if (task || requiredTask)
		{
			return Kind.TASK;
		}
		return Kind.NOTE;
	}

	private static boolean isRouteFragment(String clause)
	{
		String text = trimBoundary(clause);
		return text.matches("(?i)^(?:to|back\\s+to|again\\s+to|take\\s+to)\\s+\\S.*")
			|| text.matches("(?i)^(?:directly\\s+)?(?:north|south|east|west|north[- ]?east|"
				+ "north[- ]?west|south[- ]?east|south[- ]?west)\\b.*")
			|| (text.length() <= 80 && !text.isEmpty()
				&& Character.isUpperCase(text.charAt(0))
				&& !TASK_ANY.matcher(text).find() && !TAKE_ITEM.matcher(text).find()
				&& !SPELLBOOK_ACTION.matcher(text).find());
	}

	private static String normalizeEllipticalText(String clause, Kind previousAction)
	{
		String text = trimBoundary(clause);
		if (previousAction == Kind.TRAVEL && isRouteFragment(text))
		{
			if (text.matches("(?i)^take\\s+to\\s+.*"))
			{
				return text.replaceFirst("(?i)^take\\s+to\\s+", "Travel to ");
			}
			if (text.matches("(?i)^to\\s+.*") || text.matches("(?i)^back\\s+to\\s+.*"))
			{
				return "Travel " + text;
			}
			if (text.matches("(?i)^(?:directly\\s+)?(?:north|south|east|west|north[- ]?east|"
				+ "north[- ]?west|south[- ]?east|south[- ]?west)\\b.*"))
			{
				return "Head " + text;
			}
			if (!ACTION_HEAD.matcher(text).find() && !TRAVEL_HEAD.matcher(text).find())
			{
				return "Travel to " + text;
			}
		}
		return text;
	}

	private static boolean isRiskyLeadingText(String clause)
	{
		String text = clause == null ? "" : clause.trim();
		if (text.isEmpty())
		{
			return false;
		}
		// Optional/advisory rows describe choices, not one required execution path.
		if (ADVISORY_LEAD.matcher(text).find() || OPTIONAL.matcher(text).find()
			|| text.matches("(?i)^\\s*(?:if|unless)\\b.*"))
		{
			return true;
		}
		// An unclassified action before travel must not be silently discarded.
		return TASK_ANY.matcher(text).find() || TAKE_ITEM.matcher(text).find()
			|| text.matches("(?i).*\\b(?:right\\s*click|choose|chose|select|send|attack|peek|peak)\\b.*");
	}

	private static boolean isBenignTravelContext(String clause)
	{
		String text = clause == null ? "" : clause.trim();
		return text.matches("(?i)^\\s*(?:once\\b|after\\b|at\\s+the\\s+end\\b|from\\s+here\\b).*" )
			&& !TASK_ANY.matcher(text).find() && !TAKE_ITEM.matcher(text).find();
	}

	/**
	 * A fixed transport node can be only the access leg for a later named
	 * destination. Preserve both phases instead of allowing arrival at the ring
	 * itself to prove that the final destination was reached.
	 */
	private static String normalizeExplicitFinalDestination(String value)
	{
		if (value == null || value.isEmpty())
		{
			return value == null ? "" : value;
		}
		return FAIRY_RING_TO_FINAL_DESTINATION.matcher(value)
			.replaceAll("$1, then travel to ");
	}

	private static boolean isHarmlessIgnoredClause(String clause)
	{
		String text = clause == null ? "" : clause.trim();
		return text.matches("(?is)^\\(\\s*items?\\s*:.*\\)\\s*$")
			|| text.matches("^\\[[^\\]]+\\]$")
			|| text.matches("(?is)^\\((?:optional|option|e\\.g\\.|for example|formerly|right-click).*\\)$");
	}

	private static List<String> mergeAccessCodeClauses(List<String> input)
	{
		if (input.size() < 2)
		{
			return input;
		}
		List<String> out = new ArrayList<>();
		for (int i = 0; i < input.size(); i++)
		{
			String current = input.get(i).trim();
			if (i + 1 < input.size() && ACCESS_METHOD_ONLY.matcher(current).matches()
				&& (FAIRY_CODE_ONLY.matcher(input.get(i + 1)).matches()
					|| isSimpleDestinationFragment(input.get(i + 1))))
			{
				out.add(current + " -> " + input.get(++i).trim());
			}
			else
			{
				out.add(current);
			}
		}
		return out;
	}

	private static boolean isSimpleDestinationFragment(String value)
	{
		String text = value == null ? "" : value.trim();
		return text.length() > 2 && text.length() <= 80
			&& !ACTION_HEAD.matcher(text).find() && !TASK_ANY.matcher(text).find()
			&& !SPELLBOOK_ACTION.matcher(text).find()
			&& !text.matches("(?i).*\\b(?:items?|diary|option|optional)\\s*:.*");
	}

	private static String stripOptionalParentheticals(String value)
	{
		if (value == null || value.indexOf('(') < 0)
		{
			return value == null ? "" : value;
		}
		StringBuilder out = new StringBuilder(value.length());
		int depth = 0;
		int start = -1;
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if (c == '(')
			{
				if (depth++ == 0)
				{
					start = i;
				}
			}
			else if (c == ')' && depth > 0)
			{
				if (--depth == 0 && start >= 0)
				{
					String body = value.substring(start + 1, i);
					if (!OPTIONAL.matcher(body).find())
					{
						out.append(value, start, i + 1);
					}
					start = -1;
				}
			}
			else if (depth == 0)
			{
				out.append(c);
			}
		}
		if (depth > 0 && start >= 0)
		{
			out.append(value.substring(start));
		}
		return out.toString();
	}

	private static String stripLeadingContext(String value)
	{
		String text = value.trim();
		String[] patterns = {
			"(?i)^at\\s+the\\s+end\\s+of\\b[^,.;]{0,100}[,;:]?\\s*",
			"(?i)^after\\b[^,.;]{0,100}[,;:]\\s*",
			"(?i)^after\\s+[^,.;]{1,100}?\\s+(?=(?:use|take|tele(?:port)?|go|head|run|walk|"
				+ "travel|return|fairy\\s+ring|spirit\\s+tree)\\b)",
			"(?i)^once\\b[^,.;]{0,100}[,;:]\\s*",
			"(?i)^if\\s+you\\s+were\\b[^,.;]{0,100}[,;:]\\s*(?:now\\s+)?",
			"(?i)^from\\s+here[,;:]?\\s*",
			"(?i)^(?:then|next|finally|quickly|immediately|now)\\s+"
		};
		boolean changed;
		do
		{
			changed = false;
			for (String pattern : patterns)
			{
				String replaced = text.replaceFirst(pattern, "").trim();
				if (!replaced.equals(text))
				{
					text = replaced;
					changed = true;
				}
			}
		}
		while (changed);
		return text;
	}

	private static List<String> splitClauses(String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			return Collections.emptyList();
		}
		boolean[] protectedChars = protectedCharacters(text);
		List<Boundary> boundaries = new ArrayList<>();
		for (int i = 0; i < text.length(); i++)
		{
			if (protectedChars[i])
			{
				continue;
			}
			if (text.startsWith("->", i))
			{
				boundaries.add(new Boundary(i, i + 2));
				i++;
			}
			else if (text.charAt(i) == '→' || text.charAt(i) == ';' || text.charAt(i) == '\n')
			{
				boundaries.add(new Boundary(i, i + 1));
			}
			else if (text.charAt(i) == '.'
				&& (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))
				&& !isAcronymPeriod(text, i))
			{
				boundaries.add(new Boundary(i, i + 1));
			}
		}

		Matcher strong = Pattern.compile("(?i)\\b(?:then|afterwards|after\\s+that|followed\\s+by)\\b")
			.matcher(text);
		while (strong.find())
		{
			if (!isProtected(protectedChars, strong.start(), strong.end()))
			{
				boundaries.add(new Boundary(strong.start(), strong.end()));
			}
		}

		Matcher conditional = Pattern.compile("(?i),|&|\\band\\b|\\bthere\\s+(?=(?:use|open|talk|speak|"
			+ "activate|search|read|pay|give|collect|buy|kill|enter|climb)\\b)").matcher(text);
		while (conditional.find())
		{
			if (!isProtected(protectedChars, conditional.start(), conditional.end())
				&& (conditional.group().toLowerCase(Locale.ROOT).startsWith("there")
					|| startsWithAction(text.substring(conditional.end()))))
			{
				boundaries.add(new Boundary(conditional.start(), conditional.end()));
			}
		}

		Collections.sort(boundaries);
		List<String> out = new ArrayList<>();
		int start = 0;
		for (Boundary boundary : boundaries)
		{
			if (boundary.start < start)
			{
				continue;
			}
			String clause = trimBoundary(text.substring(start, boundary.start));
			if (!clause.isEmpty())
			{
				out.add(clause);
			}
			start = boundary.end;
		}
		String tail = trimBoundary(text.substring(start));
		if (!tail.isEmpty())
		{
			out.add(tail);
		}
		return out;
	}

	private static boolean startsWithAction(String value)
	{
		String text = value == null ? "" : value;
		return ACTION_HEAD.matcher(text).find()
			|| text.matches("(?i)^\\s*(?:boat|ship|portal|minecart|glider|quetzal|canoe|ferry|"
				+ "rowboat|raft|spirit\\s+tree|fairy\\s+ring)\\s+(?:to|towards?|into|back)\\b.*");
	}

	private static boolean isAcronymPeriod(String text, int index)
	{
		if (index <= 0 || !Character.isLetter(text.charAt(index - 1)))
		{
			return false;
		}
		int from = Math.max(0, index - 6);
		String prefix = text.substring(from, index + 1);
		return prefix.matches("(?i).*(?:[a-z]\\.){2,}[a-z]?\\.?$");
	}

	private static String trimBoundary(String value)
	{
		return value == null ? "" : value
			.replaceFirst("(?i)^[\\s,;:\\-]+(?:and\\s+)?", "")
			.replaceFirst("(?i)(?:,?\\s+and)?[\\s,;:\\-]+$", "").trim();
	}

	private static boolean[] protectedCharacters(String text)
	{
		boolean[] out = new boolean[text.length()];
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (quote != 0)
			{
				out[i] = true;
				if (c == quote && (i == 0 || text.charAt(i - 1) != '\\'))
				{
					quote = 0;
				}
				continue;
			}
			if ((c == '"' || c == '`')
				|| (c == '\'' && !(i > 0 && i + 1 < text.length()
					&& Character.isLetterOrDigit(text.charAt(i - 1))
					&& Character.isLetterOrDigit(text.charAt(i + 1)))))
			{
				quote = c;
				out[i] = true;
				continue;
			}
			if (c == '(' || c == '[' || c == '{')
			{
				depth++;
				out[i] = true;
			}
			else if (c == ')' || c == ']' || c == '}')
			{
				out[i] = true;
				depth = Math.max(0, depth - 1);
			}
			else if (depth > 0)
			{
				out[i] = true;
			}
		}
		return out;
	}

	private static boolean isProtected(boolean[] protectedChars, int start, int end)
	{
		for (int i = Math.max(0, start); i < Math.min(protectedChars.length, end); i++)
		{
			if (protectedChars[i])
			{
				return true;
			}
		}
		return false;
	}

	private static ParentheticalScan scanParentheticals(String text)
	{
		boolean itemPreparation = ITEM_SUFFIX.matcher(text).find();
		boolean requiredAction = false;
		int depth = 0;
		int start = -1;
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c == '(')
			{
				if (depth++ == 0)
				{
					start = i + 1;
				}
			}
			else if (c == ')' && depth > 0 && --depth == 0 && start >= 0)
			{
				String body = text.substring(start, i).trim();
				if (!body.toLowerCase(Locale.ROOT).startsWith("items:")
					&& !OPTIONAL.matcher(body).find()
					&& !body.matches("(?i)^(?:option|e\\.g\\.|for example|formerly|right-click).*"))
				{
					String withoutTransport = TAKE_TRANSPORT.matcher(body).replaceAll("");
					String parentheticalInstruction = stripLeadingContext(withoutTransport);
					requiredAction |= TASK_HEAD.matcher(parentheticalInstruction).find()
						|| RETURN_ITEM.matcher(parentheticalInstruction).find()
						|| TAKE_ITEM.matcher(parentheticalInstruction).find();
				}
				start = -1;
			}
		}
		return new ParentheticalScan(itemPreparation, requiredAction);
	}

	private static String clean(String value)
	{
		if (value == null)
		{
			return "";
		}
		return WIKI_LINK.matcher(value).replaceAll("$1").trim();
	}

	private static final class ParentheticalScan
	{
		private final boolean itemPreparation;
		private final boolean requiredAction;

		private ParentheticalScan(boolean itemPreparation, boolean requiredAction)
		{
			this.itemPreparation = itemPreparation;
			this.requiredAction = requiredAction;
		}
	}

	private static final class Boundary implements Comparable<Boundary>
	{
		private final int start;
		private final int end;

		private Boundary(int start, int end)
		{
			this.start = start;
			this.end = end;
		}

		@Override
		public int compareTo(Boundary other)
		{
			int byStart = Integer.compare(start, other.start);
			return byStart != 0 ? byStart : Integer.compare(end, other.end);
		}
	}
}
