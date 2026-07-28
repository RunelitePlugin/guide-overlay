package com.hcimguide;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Corpus-level invariants for {@link StepPhaseParser}.
 *
 * <p>These exist because the classifier decides, for every row in the bundled
 * guides, whether reaching a destination is allowed to tick a checklist row.
 * A single misclassification is a premature completion, which is the worst
 * failure this plugin has: it tells a player a step is done when it is not.
 * Per-row tests cannot catch a rule that quietly widens; a whole-corpus check
 * can.</p>
 *
 * <p>Deliberately depends on {@code StepPhaseParser} alone. Every other class in
 * the phase system reaches the RuneLite API, so a test touching them cannot run
 * outside a full client build. Keeping this file dependency-free means the
 * invariants are checked on every ordinary {@code gradlew test}.</p>
 */
public class StepPhaseCorpusTest
{
	/**
	 * Rows that must stay non-completable. Each was reviewed by hand: reaching
	 * the destination proves nothing about the action the row also requires.
	 */
	private static final String[] MUST_NOT_COMPLETE_ON_ARRIVAL = {
		"Teleport to Varrock and talk to Aubury",
		"Take the boat to Brimhaven, then buy a swamp paste",
		"Head North and take Jug of Water [Monk's Friend]",
		"Break another POH tab and return to Thurgo.",
		"Right click House Teleport, choose Outside House, then head east",
		"Use Star on Experiment Entrance (Don't enter)",
		"Run South and kill 5 Rats",
		"Teleport to Camelot and complete the Seers diary",
	};

	/**
	 * Rows the CLASSIFIER calls travel-only but which are protected at runtime by
	 * a different gate. "Go back to Draynor bank. (Items: ...)" reads as pure
	 * travel because going to the bank IS the instruction; the items suffix is a
	 * parsed item condition, and ArrivalCompletionPolicy.canComplete rejects any
	 * row carrying one. Recorded here so the distinction is deliberate rather
	 * than discovered later: the mode alone is not the safety boundary.
	 */
	private static final String[] TRAVEL_BUT_GATED_BY_ITEM_CONDITION = {
		"Go back to Draynor bank. (Items: 3445 gp, fishing net, bucket)",
		"Go to the Varrock East bank. (Items: 3650 gp, Chronicle, bucket of milk)",
	};

	@Test
	public void itemSuffixRowsRelyOnTheConditionGateNotTheMode()
	{
		for (String row : TRAVEL_BUT_GATED_BY_ITEM_CONDITION)
		{
			// documents current behaviour: the parser sees travel, and the
			// runtime item-condition gate is what prevents completion
			assertEquals(row, StepPhaseParser.Mode.PURE_TRAVEL,
				StepPhaseParser.parse(row).getMode());
		}
	}

	/** Rows where arrival genuinely is the whole instruction. */
	private static final String[] MAY_COMPLETE_ON_ARRIVAL = {
		"Head North",
		"Home teleport to Lumbridge",
		"Head West to the Hunter's Guild",
		"Take the boat to Rimmington",
		"Run to Draynor Village",
	};

	@Test
	public void compoundRowsAreNeverPureTravel()
	{
		for (String row : MUST_NOT_COMPLETE_ON_ARRIVAL)
		{
			assertFalse("must not be arrival-completable: " + row,
				StepPhaseParser.parse(row).getMode() == StepPhaseParser.Mode.PURE_TRAVEL);
		}
	}

	@Test
	public void travelOnlyRowsRemainPureTravel()
	{
		for (String row : MAY_COMPLETE_ON_ARRIVAL)
		{
			assertEquals("should stay arrival-completable: " + row,
				StepPhaseParser.Mode.PURE_TRAVEL, StepPhaseParser.parse(row).getMode());
		}
	}

	/**
	 * The property that made GUIDANCE_ONLY safe to introduce. These rows produce
	 * phases so the plugin can target the right clause, but the mode itself must
	 * keep them out of the arrival-completion path.
	 */
	@Test
	public void guidanceOnlyIsNeverPureTravel()
	{
		String[] guidanceRows = {
			"Right click House Teleport, choose Outside House, then head east",
			"Break another POH tab and return to Thurgo.",
			"Use Star on Experiment Entrance (Don't enter)",
		};
		for (String row : guidanceRows)
		{
			StepPhaseParser.Mode mode = StepPhaseParser.parse(row).getMode();
			assertTrue("expected GUIDANCE_ONLY or MANUAL for: " + row,
				mode == StepPhaseParser.Mode.GUIDANCE_ONLY
					|| mode == StepPhaseParser.Mode.MANUAL);
			assertFalse("guidance rows must not be arrival-completable: " + row,
				mode == StepPhaseParser.Mode.PURE_TRAVEL);
		}
	}

	/** Every parsed row must keep at least one clause; silent loss hides text. */
	@Test
	public void everyRowRetainsAtLeastOneClause()
	{
		List<String> all = new ArrayList<>();
		for (String row : MUST_NOT_COMPLETE_ON_ARRIVAL)
		{
			all.add(row);
		}
		for (String row : MAY_COMPLETE_ON_ARRIVAL)
		{
			all.add(row);
		}
		for (String row : all)
		{
			assertFalse("no clause retained for: " + row,
				StepPhaseParser.parse(row).getClauses().isEmpty());
		}
	}

	/**
	 * A task may only ever be the final actionable clause. Anything else means a
	 * later travel leg is stranded behind an action with no automatic signal.
	 */
	@Test
	public void travelThenTaskEndsWithTheTask()
	{
		String[] rows = {
			"Teleport to Varrock and talk to Aubury",
			"Take the boat to Brimhaven, then buy a swamp paste",
			"Run south and meet the scout outside the gate",
		};
		for (String row : rows)
		{
			StepPhaseParser.Parsed parsed = StepPhaseParser.parse(row);
			if (parsed.getMode() != StepPhaseParser.Mode.TRAVEL_THEN_TASK)
			{
				continue;
			}
			List<StepPhaseParser.Clause> actionable = new ArrayList<>();
			for (StepPhaseParser.Clause clause : parsed.getClauses())
			{
				if (clause.getKind() != StepPhaseParser.Kind.NOTE)
				{
					actionable.add(clause);
				}
			}
			assertFalse("no actionable clause: " + row, actionable.isEmpty());
			assertEquals("task must be last for: " + row,
				StepPhaseParser.Kind.TASK,
				actionable.get(actionable.size() - 1).getKind());
			for (int i = 0; i + 1 < actionable.size(); i++)
			{
				assertFalse("task before the final clause in: " + row,
					actionable.get(i).getKind() == StepPhaseParser.Kind.TASK);
			}
		}
	}

	/** Parsing must be deterministic; a mode that varies per call is unusable. */
	@Test
	public void classificationIsStableAcrossRepeatedParses()
	{
		Map<StepPhaseParser.Mode, Integer> first = new EnumMap<>(StepPhaseParser.Mode.class);
		for (String row : MUST_NOT_COMPLETE_ON_ARRIVAL)
		{
			first.merge(StepPhaseParser.parse(row).getMode(), 1, Integer::sum);
		}
		Map<StepPhaseParser.Mode, Integer> second = new EnumMap<>(StepPhaseParser.Mode.class);
		for (String row : MUST_NOT_COMPLETE_ON_ARRIVAL)
		{
			second.merge(StepPhaseParser.parse(row).getMode(), 1, Integer::sum);
		}
		assertEquals(first, second);
	}
}
