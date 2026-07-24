package com.hcimguide;

import java.awt.Point;
import java.awt.Rectangle;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StepNavOverlayTest
{
	@Test
	public void locationButtonHitTestUsesScreenCoordinates()
	{
		Rectangle bounds = new Rectangle(100, 200, 92, 22);
		Rectangle local = new Rectangle(32, 0, 28, 22);
		assertTrue(StepNavOverlay.hitButton(new Point(140, 210), bounds, local));
		assertFalse(StepNavOverlay.hitButton(new Point(105, 210), bounds, local));
	}

	@Test
	public void maxControlWidthIncludesThreeButtons()
	{
		assertTrue(StepNavOverlay.totalWidth(true) > StepNavOverlay.totalWidth(false));
	}
}
