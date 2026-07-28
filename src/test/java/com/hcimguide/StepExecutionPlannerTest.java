package com.hcimguide;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class StepExecutionPlannerTest
{
	@Test
	public void repeatedSemanticLegsReceiveStableDistinctIds()
	{
		String key = "bank#repeated-route";
		GuideStep step = new GuideStep(key,
			"Teleport to Varrock, then teleport to Falador, then teleport to Varrock",
			0, "bank");
		Gson gson = new Gson();
		StepExecutionPlan plan = StepExecutionPlanner.build(step,
			new PlaceDirectory(gson), new TransportResolver(gson), ignored -> null);

		assertNotNull(plan);
		assertEquals(3, plan.size());
		assertNotEquals(plan.get(0).getId(), plan.get(2).getId());
		assertEquals(0, plan.indexOfId(plan.get(0).getId()));
		assertEquals(2, plan.indexOfId(plan.get(2).getId()));
	}
	@Test
	public void oneBareTravelPhaseMayBorrowAProvenParentDestination()
	{
		String key = "bank#parent-fallback";
		GuideStep step = new GuideStep(key, "Teleport and bank at Camelot", 0, "bank");
		StepLocationHint camelot = new StepLocationHint("Camelot",
			new net.runelite.api.coords.WorldPoint(2757, 3479, 0), false);
		StepLocationPlan parent = new StepLocationPlan(key,
			java.util.Collections.singletonList(camelot));
		Gson gson = new Gson();
		StepExecutionPlan plan = StepExecutionPlanner.build(step,
			new PlaceDirectory(gson), new TransportResolver(gson), ignored -> null, parent);

		assertNotNull(plan);
		assertEquals(2, plan.size());
		assertEquals(StepPhaseParser.Kind.TRAVEL, plan.get(0).getKind());
		assertEquals(StepPhaseParser.Kind.TASK, plan.get(1).getKind());
	}

}
