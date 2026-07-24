package com.hcimguide;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class LocationAuditTest
{
	@Test
	public void partiallyResolvedRouteStillReportsUnknownFinalStop()
	{
		Guide guide = new Guide();
		GuideEpisode episode = new GuideEpisode(1, "Episode");
		GuideBank bank = new GuideBank("E1.B1", "Bank 1", 1);
		GuideStep step = new GuideStep("E1.B1#route", "Go to Ferox, then travel to the unknown shrine", 0, bank.getId());
		bank.getSteps().add(step);
		episode.getBanks().add(bank);
		guide.getEpisodes().add(episode);

		StepLocationHint known = new StepLocationHint("Ferox Enclave", new WorldPoint(3150, 3635, 0), false);
		Map<String, StepLocationPlan> plans = new HashMap<>();
		plans.put(step.getKey(), new StepLocationPlan(step.getKey(), Collections.singletonList(known),
			LocationResolutionReason.UNKNOWN_NAMED_PLACE,
			Collections.singletonList("travel to the unknown shrine")));

		String markdown = LocationAudit.toMarkdown("guide", guide, plans);
		assertTrue(markdown.contains("Unresolved actionable steps: 1"));
		assertTrue(markdown.contains("travel to the unknown shrine"));
		assertTrue(markdown.contains("E1.B1#route"));
	}
}
