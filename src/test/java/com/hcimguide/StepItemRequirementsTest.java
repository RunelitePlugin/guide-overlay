package com.hcimguide;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StepItemRequirementsTest
{
	@Test
	public void jsonItemsSuffixUsesTheSameDisplayProjection()
	{
		GuideStep step = new GuideStep("suffix", "Talk to Bob. (Items: knife, 2 ropes)", 0, null);
		List<ItemReq> reqs = StepItemRequirements.displayFor(step);
		assertNotNull(reqs);
		assertEquals(2, reqs.size());
		assertEquals("knife", ItemReq.normalize(reqs.get(0).getName()));
		assertEquals(2, reqs.get(1).getQuantity());
		// the projection preserves the guide's own wording ("2 ropes"), and
		// normalize() only lowercases - it does not singularise. The contract
		// worth pinning is that the requirement is equivalent to Rope.
		assertEquals("ropes", ItemReq.normalize(reqs.get(1).getName()));
		assertTrue(ItemReq.namesEquivalent(reqs.get(1).getName(), "Rope"));
	}

	@Test
	public void conditionItemsTakePriorityOverDisplaySuffix()
	{
		String text = "Withdraw: Coins, Rope (Items: knife)";
		StepCondition condition = ConditionParser.parse(text);
		List<ItemReq> reqs = StepItemRequirements.displayFor(text, condition);
		assertNotNull(reqs);
		assertEquals("coins", ItemReq.normalize(reqs.get(0).getName()));
		assertEquals("rope", ItemReq.normalize(reqs.get(1).getName()));
	}
}
