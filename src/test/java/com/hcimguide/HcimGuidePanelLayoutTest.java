package com.hcimguide;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HcimGuidePanelLayoutTest
{
	@Test
	public void contentMarginsMatchRuneLiteSettingsPanel()
	{
		// ConfigPanel uses new EmptyBorder(8, 10, 10, 10) around its content
		assertEquals(8, PanelLayout.CONTENT_MARGIN_TOP);
		assertEquals(10, PanelLayout.CONTENT_MARGIN_LEFT);
		assertEquals(10, PanelLayout.CONTENT_MARGIN_BOTTOM);
		assertEquals(10, PanelLayout.CONTENT_MARGIN_RIGHT);
	}

	@Test
	public void textClearanceIsTwelvePixels()
	{
		assertEquals(12, PanelLayout.CONTENT_MARGIN_RIGHT + PanelLayout.TEXT_RIGHT_SAFETY);
	}

	@Test
	public void preLayoutEstimateIsNarrowerThanTheRealColumn()
	{
		// deliberately conservative: must be positive but never wider than the
		// content width minus the margins alone
		int estimate = PanelLayout.preLayoutTextWidth();
		assertTrue(estimate > 0);
		assertTrue(estimate < PanelLayout.PANEL_CONTENT_WIDTH
			- PanelLayout.CONTENT_MARGIN_LEFT - PanelLayout.CONTENT_MARGIN_RIGHT);
	}

	@Test
	public void middleRowCentersOnViewportCenter()
	{
		// row at y=1000, 20 tall; viewport 300; view 5000
		int y = PanelLayout.centeredViewPosition(1000, 20, 300, 5000);
		// row center (1010) should sit at viewport center (y + 150)
		assertEquals(1010, y + 150);
	}

	@Test
	public void firstRowClampsToTop()
	{
		assertEquals(0, PanelLayout.centeredViewPosition(10, 20, 300, 5000));
	}

	@Test
	public void lastRowClampsToBottom()
	{
		int y = PanelLayout.centeredViewPosition(4950, 20, 300, 5000);
		assertEquals(4700, y); // viewHeight - viewportHeight
	}

	@Test
	public void rowTallerThanViewportAlignsItsTop()
	{
		assertEquals(1000, PanelLayout.centeredViewPosition(1000, 400, 300, 5000));
	}

	@Test
	public void shortViewNeverScrollsNegative()
	{
		assertEquals(0, PanelLayout.centeredViewPosition(50, 20, 300, 200));
	}

	@Test
	public void repeatedCenteringIsStable()
	{
		int first = PanelLayout.centeredViewPosition(2000, 30, 300, 5000);
		int second = PanelLayout.centeredViewPosition(2000, 30, 300, 5000);
		assertEquals(first, second);
	}
}
