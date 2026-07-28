package com.hcimguide;

import java.awt.Polygon;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TargetOverlayGeometryTest
{
	@Test
	public void targetArrowPointsDownAtRequestedTip()
	{
		Polygon arrow = TargetOverlay.targetArrowPolygon(100, 80, 16);
		assertEquals(3, arrow.npoints);
		assertEquals(100, arrow.xpoints[2]);
		assertEquals(80, arrow.ypoints[2]);
		assertTrue(arrow.xpoints[0] < 100);
		assertTrue(arrow.xpoints[1] > 100);
		assertTrue(arrow.ypoints[0] < 80);
		assertEquals(arrow.ypoints[0], arrow.ypoints[1]);
	}
}
