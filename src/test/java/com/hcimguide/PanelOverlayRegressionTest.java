package com.hcimguide;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Sidebar panel and overlay behaviour that only shows up with realistic
 * guide shapes: long section titles, sections with hundreds of steps, and
 * large fonts. Everything runs headless - components are laid out by hand
 * and overlays render into an off-screen image.
 */
public class PanelOverlayRegressionTest
{
	private static final String LONG_TITLE = "1.1: Tutorial island up to and including Wintertodt ";

	/** The plugin surface the panel and overlays read, backed by plain collections. */
	private static class Stub extends HcimGuidePlugin
	{
		final Set<String> done = new HashSet<>();
		final Map<String, List<ItemReq>> items = new HashMap<>();
		Guide guide;
		GuideBank hudBank;
		boolean refuseWrites;

		@Override
		boolean isCompleted(String key)
		{
			return done.contains(key);
		}

		@Override
		boolean isSkipped(String key)
		{
			return false;
		}

		@Override
		boolean isStepDone(String key)
		{
			return done.contains(key);
		}

		@Override
		void setCompleted(String key, boolean completed)
		{
			if (refuseWrites)
			{
				return;
			}
			if (completed)
			{
				done.add(key);
			}
			else
			{
				done.remove(key);
			}
		}

		@Override
		int[] progressOf(Iterable<GuideStep> steps)
		{
			int complete = 0;
			int total = 0;
			for (GuideStep step : steps)
			{
				total++;
				if (done.contains(step.getKey()))
				{
					complete++;
				}
			}
			return new int[]{complete, total};
		}

		@Override
		String getActiveBankId()
		{
			for (GuideEpisode ep : guide.getEpisodes())
			{
				for (GuideBank bank : ep.getBanks())
				{
					for (GuideStep step : bank.getSteps())
					{
						if (!done.contains(step.getKey()))
						{
							return bank.getId();
						}
					}
				}
			}
			return null;
		}

		@Override
		StepCondition getCondition(String key)
		{
			return null;
		}

		@Override
		String getStepTarget(String key)
		{
			return null;
		}

		@Override
		List<ItemReq> getStepItems(String key)
		{
			return items.get(key);
		}

		@Override
		String getPinnedStepKey()
		{
			return null;
		}

		@Override
		InventorySnapshot getInventorySnapshot()
		{
			return InventorySnapshot.EMPTY;
		}

		@Override
		void resolveItemIcons(ItemGridPanel grid)
		{
		}

		@Override
		String getCurrentGuideId()
		{
			return "test-guide";
		}

		@Override
		boolean isGuideImported(String guideId)
		{
			return true;
		}

		@Override
		GuideBank getActiveBank()
		{
			return hudBank;
		}

		@Override
		boolean isTripReady()
		{
			return false;
		}

		@Override
		String getRouteSuggestion()
		{
			return null;
		}

		@Override
		boolean isActiveStopTransport()
		{
			return false;
		}

		@Override
		String getWaypointCounterSnapshot()
		{
			return null;
		}

		@Override
		boolean hasLocationForGuidedStep()
		{
			return true;
		}

		@Override
		boolean isLocationGuideHiddenForGuidedStep()
		{
			return false;
		}

		@Override
		HcimGuidePlugin.HudItems getHudStepItems()
		{
			return null;
		}
	}

	private static final HcimGuideConfig DEFAULTS = new HcimGuideConfig()
	{
	};

	/** One episode of {@code banks} sections; step text ends "marker&lt;bank&gt;x&lt;step&gt;". */
	private static Guide guide(Stub plugin, int banks, int steps, String titlePrefix, boolean withItems)
	{
		Guide guide = new Guide();
		GuideEpisode episode = new GuideEpisode(1, "Episode 1");
		guide.getEpisodes().add(episode);
		for (int b = 1; b <= banks; b++)
		{
			GuideBank bank = new GuideBank("b" + b, titlePrefix + b, 1);
			for (int s = 1; s <= steps; s++)
			{
				String key = "b" + b + "#" + s;
				bank.getSteps().add(new GuideStep(key, "Withdraw the runes, staff and food for trip " + b + "." + s
					+ " then walk north to the bank chest and deposit everything marker" + b + "x" + s, 0, "b" + b));
				if (withItems)
				{
					plugin.items.put(key, Arrays.asList(new ItemReq("Law rune", 1), new ItemReq("Air rune", 5),
						new ItemReq("Dramen staff", 1), new ItemReq("Knife", 1), new ItemReq("Spade", 1)));
				}
			}
			episode.getBanks().add(bank);
		}
		plugin.guide = guide;
		return guide;
	}

	// ------------------------------------------------------------------ Swing plumbing

	private interface Body
	{
		void run() throws Exception;
	}

	private static void edt(Body body) throws Exception
	{
		Throwable[] failure = new Throwable[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				body.run();
			}
			catch (Throwable t)
			{
				failure[0] = t;
			}
		});
		if (failure[0] instanceof Error)
		{
			throw (Error) failure[0];
		}
		if (failure[0] != null)
		{
			throw new RuntimeException(failure[0]);
		}
	}

	/** Headless stand-in for Swing validation: top-down layout with fresh layout caches. */
	private static void layout(Component component)
	{
		if (component instanceof Container)
		{
			Container container = (Container) component;
			if (container.getLayout() instanceof java.awt.LayoutManager2)
			{
				((java.awt.LayoutManager2) container.getLayout()).invalidateLayout(container);
			}
			container.doLayout();
			for (Component child : container.getComponents())
			{
				layout(child);
			}
		}
	}

	/** Enough passes for wrapped text to settle on its real width. */
	private static void settle(Component component)
	{
		for (int i = 0; i < 6; i++)
		{
			layout(component);
		}
	}

	private static <T> List<T> all(Component root, Class<T> type)
	{
		List<T> out = new ArrayList<>();
		collect(root, type, out);
		return out;
	}

	private static <T> void collect(Component component, Class<T> type, List<T> out)
	{
		if (type.isInstance(component))
		{
			out.add(type.cast(component));
		}
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				collect(child, type, out);
			}
		}
	}

	private static JViewport viewport(HcimGuidePanel panel)
	{
		return all(panel, JScrollPane.class).get(0).getViewport();
	}

	/** The section list: scroll view -> margin panel -> list. */
	private static Container list(HcimGuidePanel panel)
	{
		Container wrapper = (Container) viewport(panel).getView();
		Container content = (Container) wrapper.getComponent(0);
		return (Container) content.getComponent(0);
	}

	private static Container section(HcimGuidePanel panel, int index)
	{
		return (Container) list(panel).getComponent(index);
	}

	private static JPanel header(Container section)
	{
		return (JPanel) section.getComponent(0);
	}

	private static Container body(Container section)
	{
		return (Container) section.getComponent(1);
	}

	private static boolean expanded(Container section)
	{
		return body(section).isVisible();
	}

	private static int builtRows(Container section)
	{
		return body(section).getComponentCount();
	}

	private static JLabel stepText(Component row)
	{
		for (JLabel label : all(row, JLabel.class))
		{
			if (label.getText() != null && label.getText().startsWith("<html>"))
			{
				return label;
			}
		}
		throw new AssertionError("row has no step text label");
	}

	private static JLabel labelContaining(Component root, String text)
	{
		for (JLabel label : all(root, JLabel.class))
		{
			if (label.getText() != null && label.getText().contains(text))
			{
				return label;
			}
		}
		return null;
	}

	private static void click(JPanel header)
	{
		MouseEvent press = new MouseEvent(header, MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1);
		for (MouseListener listener : header.getMouseListeners())
		{
			listener.mousePressed(press);
		}
	}

	private static void search(HcimGuidePanel panel, String text)
	{
		all(panel, JTextField.class).get(0).setText(text);
		panel.flushSearch();
	}

	private static HcimGuidePanel panel(Stub plugin, HcimGuideConfig config, Guide guide)
	{
		HcimGuidePanel panel = new HcimGuidePanel(plugin, config);
		panel.setSize(242, 800);
		panel.setGuide(guide, "ok");
		settle(panel);
		return panel;
	}

	// ------------------------------------------------------------------ checklist layout

	private static void assertListLaidOutCleanly(HcimGuidePanel panel)
	{
		Container list = list(panel);
		assertEquals("first section must start at the top of the list", 0, list.getComponent(0).getY());
		Component last = list.getComponent(list.getComponentCount() - 1);
		assertTrue("last section runs past the end of the list",
			last.getY() + last.getHeight() <= list.getHeight());
		Container open = section(panel, 0);
		assertTrue(builtRows(open) > 0);
		for (Component row : body(open).getComponents())
		{
			JLabel text = stepText(row);
			assertTrue("step text clipped: has " + text.getHeight() + "px, needs " + text.getPreferredSize().height,
				text.getHeight() >= text.getPreferredSize().height);
			assertTrue("row shorter than its content", row.getHeight() >= row.getPreferredSize().height);
		}
	}

	@Test
	public void longSectionTitleDoesNotClipStepTextOrShiftTheList() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 3, 20, LONG_TITLE, true));
			assertListLaidOutCleanly(panel);
		});
	}

	@Test
	public void layoutHoldsAtALargePanelFontAndAnotherSidebarWidth() throws Exception
	{
		HcimGuideConfig large = new HcimGuideConfig()
		{
			@Override
			public int panelFontSize()
			{
				return 24;
			}
		};
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = new HcimGuidePanel(plugin, large);
			panel.setSize(300, 700);
			panel.setGuide(guide(plugin, 3, 8, LONG_TITLE, true), "ok");
			settle(panel);
			assertListLaidOutCleanly(panel);
			assertEquals("rows span the list at whatever width the sidebar has",
				list(panel).getWidth(), section(panel, 0).getWidth());
		});
	}

	@Test
	public void truncatedSectionTitleIsAvailableAsATooltip() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 2, 3, LONG_TITLE, false));
			assertEquals(LONG_TITLE + "1", header(section(panel, 0)).getToolTipText());

			Stub shortPlugin = new Stub();
			HcimGuidePanel shortPanel = panel(shortPlugin, DEFAULTS, guide(shortPlugin, 2, 3, "Bank ", false));
			assertNull("a title that fits needs no tooltip", header(section(shortPanel, 0)).getToolTipText());
		});
	}

	// ------------------------------------------------------------------ expand / collapse

	@Test
	public void refreshNeverUndoesWhatThePlayerOpenedOrClosed() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			Guide guide = guide(plugin, 5, 3, "Bank ", false);
			for (int s = 1; s <= 3; s++)
			{
				plugin.done.add("b1#" + s);
				plugin.done.add("b2#" + s);
			}
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide);
			Container finished = section(panel, 0);
			Container active = section(panel, 2);
			assertTrue(expanded(active));
			click(header(finished)); // re-open a finished bank to review it
			click(header(active));   // close the active one by hand
			panel.refreshFromModel(); // any step completing anywhere, a nav arrow, a phase advance
			assertTrue("finished bank the player opened was closed again", expanded(finished));
			assertFalse("bank the player closed by hand was reopened", expanded(active));
		});
	}

	@Test
	public void finishingASectionCollapsesItOnceAndOpensTheNext() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 4, 3, "Bank ", false));
			Container first = section(panel, 0);
			Container second = section(panel, 1);
			assertTrue(expanded(first));
			for (int s = 1; s <= 3; s++)
			{
				plugin.done.add("b1#" + s);
			}
			panel.refreshFromModel();
			assertFalse("a section that just finished auto-collapses", expanded(first));
			assertTrue("...and the next unfinished one opens", expanded(second));

			click(header(first)); // review the finished section
			panel.refreshFromModel();
			assertTrue("no second collapse without a new completion", expanded(first));
		});
	}

	@Test
	public void aSectionClosedByHandIsNotReopenedWhenThePreviousOneFinishes() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 4, 3, "Bank ", false));
			Container second = section(panel, 1);
			click(header(second));
			click(header(second)); // opened, then closed again by hand
			for (int s = 1; s <= 3; s++)
			{
				plugin.done.add("b1#" + s);
			}
			panel.refreshFromModel();
			assertFalse(expanded(second));
		});
	}

	@Test
	public void collapsingASectionReleasesItsRows() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 3, 5, "Bank ", true));
			Container third = section(panel, 2);
			assertEquals(0, builtRows(third));
			click(header(third));
			assertEquals(5, builtRows(third));
			click(header(third));
			assertEquals("rows of a closed section are not retained", 0, builtRows(third));
			click(header(third));
			assertEquals("and are rebuilt on the next open", 5, builtRows(third));
		});
	}

	// ------------------------------------------------------------------ search

	@Test
	public void searchNeedsThreeCharacters() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 4, 3, "Bank ", false));
			search(panel, "wi"); // matches every step
			for (int i = 0; i < 4; i++)
			{
				assertTrue(section(panel, i).isVisible());
			}
			assertEquals("a too-short query builds nothing", 0, builtRows(section(panel, 2)));
			search(panel, "marker3x2");
			assertFalse(section(panel, 1).isVisible());
			assertTrue(section(panel, 2).isVisible());
		});
	}

	@Test
	public void searchBuildsOnlyMatchingRowsAndClearingRestoresTheLayout() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 5, 6, "Bank ", false));
			Container active = section(panel, 0);
			Container match = section(panel, 3);
			click(header(section(panel, 4))); // a second section open before the search
			search(panel, "marker4x2");
			assertTrue(match.isVisible());
			assertTrue(expanded(match));
			assertEquals("only the matching row is built", 1, builtRows(match));
			assertFalse(active.isVisible());

			search(panel, "");
			assertTrue(active.isVisible());
			assertTrue("sections open before the search are open again", expanded(active));
			assertTrue(expanded(section(panel, 4)));
			assertEquals(6, builtRows(active));
			assertFalse("a section the search opened closes again", expanded(match));
			assertEquals(0, builtRows(match));
		});
	}

	@Test
	public void aSectionOpenedByHandDuringASearchStaysOpenAfterwards() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 5, 6, "Bank ", false));
			Container match = section(panel, 3);
			search(panel, "marker4x2");
			click(header(match)); // close the partial view
			click(header(match)); // open the whole section
			assertEquals(6, builtRows(match));
			search(panel, "");
			assertTrue(expanded(match));
		});
	}

	@Test
	public void broadSearchNeverBuildsMoreRowsThanTheBudget() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			// 12 sections of 90 steps: every step matches, each section fits the
			// budget alone, and together they exceed it several times over
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 12, 90, "Bank ", false));
			search(panel, "withdraw");
			int shownBySearch = 0;
			int collapsed = 0;
			for (int i = 1; i < 12; i++) // section 0 was open before the search
			{
				Container section = section(panel, i);
				assertTrue(section.isVisible());
				shownBySearch += builtRows(section);
				if (!expanded(section))
				{
					collapsed++;
				}
			}
			assertTrue("built " + shownBySearch + " rows for one query", shownBySearch <= 400);
			assertTrue(shownBySearch > 0);
			assertTrue(collapsed > 0);
			assertTrue(labelContaining(panel, "Broad search") != null);
		});
	}

	@Test
	public void clearingASearchOnlyReclaimsItsOwnStatus() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 12, 90, "Bank ", false));
			search(panel, "withdraw");
			assertTrue(labelContaining(panel, "Broad search") != null);
			search(panel, "");
			assertNull("the filter's own note goes with the filter", labelContaining(panel, "Broad search"));

			search(panel, "withdraw");
			panel.setStatus("Import failed: disk full");
			search(panel, "");
			assertTrue("a status shown since the note must survive", labelContaining(panel, "Import failed") != null);
		});
	}

	@Test
	public void jumpDropdownLiftsAnActiveSearch() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 6, 3, "Bank ", false));
			search(panel, "marker2x1");
			Container target = section(panel, 4);
			assertFalse(target.isVisible());
			@SuppressWarnings("unchecked")
			JComboBox<GuideBank> jump = all(panel, JComboBox.class).get(1);
			jump.setSelectedIndex(4);
			assertTrue("jump target stays hidden by the search filter", target.isVisible());
			assertTrue(expanded(target));
			assertEquals("", all(panel, JTextField.class).get(0).getText());
		});
	}

	// ------------------------------------------------------------------ rebuilds

	@Test
	public void rebuildKeepsOpenSectionsAndWhatWasInView() throws Exception
	{
		HcimGuidePanel[] holder = new HcimGuidePanel[1];
		int offset = 7;
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = new HcimGuidePanel(plugin, DEFAULTS);
			panel.setSize(242, 420);
			panel.setGuide(guide(plugin, 8, 6, "Bank ", false), "ok");
			settle(panel);
			click(header(section(panel, 3)));
			settle(panel);
			JViewport viewport = viewport(panel);
			Component row = body(section(panel, 3)).getComponent(2);
			Rectangle bounds = SwingUtilities.convertRectangle(row.getParent(), row.getBounds(), viewport.getView());
			viewport.setViewPosition(new java.awt.Point(0, bounds.y - offset));
			assertEquals(bounds.y - offset, viewport.getViewPosition().y);

			panel.onConfigChanged(); // structural rebuild
			settle(panel);
			holder[0] = panel;
		});
		// the re-anchoring runs after the rebuild's layout; let its passes drain
		for (int i = 0; i < 5; i++)
		{
			edt(() -> settle(holder[0]));
		}
		edt(() ->
		{
			HcimGuidePanel panel = holder[0];
			assertTrue("the active section stays open", expanded(section(panel, 0)));
			assertTrue("a section the player opened stays open", expanded(section(panel, 3)));
			assertFalse(expanded(section(panel, 5)));
			JViewport viewport = viewport(panel);
			Component row = body(section(panel, 3)).getComponent(2);
			Rectangle bounds = SwingUtilities.convertRectangle(row.getParent(), row.getBounds(), viewport.getView());
			assertEquals("the row in view keeps its place", offset, bounds.y - viewport.getViewPosition().y);
		});
	}

	// ------------------------------------------------------------------ header

	@Test
	public void headerControlsShareOneLeftEdgeAndTheStatusIsFullyVisible() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 3, 3, "Bank ", false));
			panel.setStatus("Guide loaded from local snapshot — 9 sections, 2343 steps"
				+ " — source exceeded a safety limit and was TRUNCATED");
			settle(panel);
			JLabel status = labelContaining(panel, "TRUNCATED");
			JTextField search = all(panel, JTextField.class).get(0);
			JButton jump = null;
			for (JButton button : all(panel, JButton.class))
			{
				if ("Jump to next unchecked step".equals(button.getText()))
				{
					jump = button;
				}
			}
			assertEquals("status line left edge", search.getX(), status.getX());
			assertEquals("jump button left edge", search.getX(), jump.getX());
			assertTrue("status wider than the header", status.getWidth() <= search.getWidth());
			assertTrue("status is cut: has " + status.getHeight() + "px, needs " + status.getPreferredSize().height,
				status.getHeight() >= status.getPreferredSize().height);
			int oneLine = status.getFontMetrics(status.getFont()).getHeight();
			assertTrue("a long status wraps onto further lines", status.getHeight() >= 2 * oneLine);

			int x = jump.getX();
			panel.setStatus(" ");
			settle(panel);
			assertEquals("controls must not move with the status text", x, jump.getX());
		});
	}

	@Test
	public void pinChangeDoesNotGuessWhetherTheTargetIsNearby() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 2, 3, "Bank ", false));
			panel.onPinChanged("Varrock west bank");
			assertEquals("Tracking: Varrock west bank", labelContaining(panel, "Tracking:").getText());
			panel.setTargetStatus("Aubury", true); // the plugin's own report still carries the verdict
			assertEquals("Tracking: Aubury (nearby!)", labelContaining(panel, "Tracking:").getText());
		});
	}

	// ------------------------------------------------------------------ step rows

	@Test
	public void checkboxShowsTheModelWhenTheStoreRefusesTheWrite() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 2, 3, "Bank ", false));
			Component row = body(section(panel, 0)).getComponent(0);
			JCheckBox box = all(row, JCheckBox.class).get(0);
			plugin.refuseWrites = true;
			box.doClick(0);
			assertFalse("box must not claim a completion the model does not hold", box.isSelected());
			assertFalse(stepText(row).getText().contains("<strike>"));

			plugin.refuseWrites = false;
			box.doClick(0);
			assertTrue(box.isSelected());
			assertTrue(plugin.done.contains("b1#1"));
		});
	}

	@Test
	public void releasingOnTheStepTextTogglesTheStep() throws Exception
	{
		edt(() ->
		{
			Stub plugin = new Stub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, guide(plugin, 2, 3, "Bank ", false));
			JLabel text = stepText(body(section(panel, 0)).getComponent(0));
			MouseEvent outside = new MouseEvent(text, MouseEvent.MOUSE_RELEASED, 0, 0,
				text.getWidth() + 20, 4, 1, false, MouseEvent.BUTTON1);
			MouseEvent inside = new MouseEvent(text, MouseEvent.MOUSE_RELEASED, 0, 0, 4, 4, 1, false, MouseEvent.BUTTON1);
			for (MouseListener listener : text.getMouseListeners())
			{
				listener.mouseReleased(outside);
			}
			assertFalse("a release outside the text is not a click on it", plugin.done.contains("b1#1"));
			for (MouseListener listener : text.getMouseListeners())
			{
				listener.mouseReleased(inside);
			}
			assertTrue(plugin.done.contains("b1#1"));
		});
	}

	// ------------------------------------------------------------------ item grid

	@Test
	public void applyIconsLeavesSwingToTheEventThread() throws Exception
	{
		List<ItemReq> reqs = Arrays.asList(new ItemReq("Combat Gear", 1), new ItemReq("Knife", 1));
		ItemGridPanel[] grid = new ItemGridPanel[1];
		SwingUtilities.invokeAndWait(() -> grid[0] = new ItemGridPanel(reqs, true));
		JLabel concept = (JLabel) grid[0].getComponent(0);
		Border before = concept.getBorder();
		int conceptSprite = -1168; // an encoded sprite id, as the resolver returns for concept slots
		assertTrue(ItemIconResolver.isSpriteId(conceptSprite));
		// hold the event thread so nothing queued for it can run before the check
		java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		SwingUtilities.invokeLater(() ->
		{
			held.countDown();
			try
			{
				release.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		});
		held.await();
		try
		{
			grid[0].applyIcons(null, null, reqs, new int[]{conceptSprite, -1});
			assertSame("border replaced on the calling (non-event) thread", before, concept.getBorder());
		}
		finally
		{
			release.countDown();
		}
		SwingUtilities.invokeAndWait(() ->
		{
		});
		assertNotSame("the concept slot's border is applied on the event thread", before, concept.getBorder());
	}

	// ------------------------------------------------------------------ HUD

	private static int[] paintedColumns(BufferedImage image)
	{
		int min = Integer.MAX_VALUE;
		int max = -1;
		int maxY = -1;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					min = Math.min(min, x);
					max = Math.max(max, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		return new int[]{min, max, maxY};
	}

	private static Object[] renderHud(HudOverlay hud)
	{
		BufferedImage image = null;
		Dimension size = null;
		// the box background is sized from the previous frame, so look at the second
		for (int frame = 0; frame < 2; frame++)
		{
			image = new BufferedImage(900, 500, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = image.createGraphics();
			graphics.translate(300, 20);
			size = hud.render(graphics);
			graphics.dispose();
		}
		return new Object[]{image, size};
	}

	@Test
	public void hudPaintsNothingOutsideItsReportedBounds()
	{
		Stub plugin = new Stub();
		Guide guide = guide(plugin, 1, 5, "2.2: Barbarian fishing, Green dragons and the Hallowed Sepulchre ", false);
		plugin.hudBank = guide.getEpisodes().get(0).getBanks().get(0);
		Object[] rendered = renderHud(new HudOverlay(plugin, DEFAULTS, null, null));
		Dimension size = (Dimension) rendered[1];
		int[] painted = paintedColumns((BufferedImage) rendered[0]);
		assertTrue("painted from x=" + painted[0] + " but the overlay starts at 300", painted[0] >= 300);
		assertTrue("painted to x=" + painted[1] + " but the overlay ends at " + (300 + size.width),
			painted[1] < 300 + size.width);
		assertTrue("painted below the reported height", painted[2] < 20 + size.height);
	}

	@Test
	public void hudWidthSettingIsClampedToItsRange()
	{
		HcimGuideConfig wild = new HcimGuideConfig()
		{
			@Override
			public int hudWidth()
			{
				return 5000;
			}

			@Override
			public int hudMaxSteps()
			{
				return -3;
			}
		};
		Stub plugin = new Stub();
		Guide guide = guide(plugin, 1, 5, "Bank ", false);
		plugin.hudBank = guide.getEpisodes().get(0).getBanks().get(0);
		Dimension size = (Dimension) renderHud(new HudOverlay(plugin, wild, null, null))[1];
		assertEquals(320, size.width);
	}

	@Test
	public void titleWrapKeepsEveryLineInsideTheBoxAndTheProgressWhole()
	{
		Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		FontMetrics metrics = graphics.getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		String title = "2.3: Wait there’s even more quests? And one of them is Song of the Elves?";
		List<String> lines = HudOverlay.wrapTitle(title, "  (12/88)", metrics, 222);
		assertTrue(lines.size() > 1);
		for (String line : lines)
		{
			assertTrue("'" + line + "' is wider than the box", metrics.stringWidth(line) <= 222);
		}
		assertTrue(lines.get(lines.size() - 1).endsWith("(12/88)"));
		assertEquals("no word may be lost", title + " (12/88)",
			String.join(" ", lines).replaceAll("\\s+", " "));

		assertEquals(Arrays.asList("Bank 72  (3/11)"), HudOverlay.wrapTitle("Bank 72", "  (3/11)", metrics, 222));
		assertEquals(Arrays.asList("(0/0)"), HudOverlay.wrapTitle("", "  (0/0)", metrics, 222));

		// one unbroken word wider than the box is cut, never left to overflow
		List<String> cut = HudOverlay.wrapTitle("Supercalifragilisticexpialidocious", "  (1/2)", metrics, 60);
		assertTrue(cut.size() > 2);
		for (String line : cut)
		{
			assertTrue(metrics.stringWidth(line) <= 60);
		}
		graphics.dispose();
	}

	@Test
	public void presenceStatesMarkHeldMissingAndConceptSlots()
	{
		Map<String, Integer> held = new HashMap<>();
		held.put("lawrune", 3);
		InventorySnapshot inventory = new InventorySnapshot(held, held);
		List<ItemReq> items = Arrays.asList(new ItemReq("Law rune", 2), new ItemReq("Knife", 1),
			new ItemReq("Combat Gear", 1), null);
		assertArrayEquals(new byte[]{HudOverlay.PRESENCE_HAVE, HudOverlay.PRESENCE_MISSING,
			HudOverlay.PRESENCE_NONE, HudOverlay.PRESENCE_NONE}, HudOverlay.presenceStates(items, inventory));
	}

	// ------------------------------------------------------------------ compass

	private static DirectionArrowOverlay compass(HcimGuideConfig config)
	{
		WorldPoint here = new WorldPoint(3200, 3200, 0);
		java.lang.reflect.InvocationHandler defaults = (proxy, method, args) ->
		{
			Class<?> type = method.getReturnType();
			return type == boolean.class ? (Object) false : type == int.class ? (Object) 0 : null;
		};
		Player player = (Player) java.lang.reflect.Proxy.newProxyInstance(
			PanelOverlayRegressionTest.class.getClassLoader(), new Class<?>[]{Player.class},
			(proxy, method, args) -> method.getName().equals("getWorldLocation")
				? here : defaults.invoke(proxy, method, args));
		Client client = (Client) java.lang.reflect.Proxy.newProxyInstance(
			PanelOverlayRegressionTest.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> method.getName().equals("getLocalPlayer")
				? player : defaults.invoke(proxy, method, args));
		Stub plugin = new Stub()
		{
			@Override
			WorldPoint getShortestPathTarget()
			{
				return new WorldPoint(2757, 3477, 1); // 443 tiles away, one floor up
			}

			@Override
			WorldPoint getFarTarget()
			{
				return null;
			}

			@Override
			boolean allowCompassGuidance(WorldPoint objective)
			{
				return true;
			}
		};
		return new DirectionArrowOverlay(client, plugin, config);
	}

	private static void assertCompassStaysInsideItsBounds(HcimGuideConfig config)
	{
		BufferedImage image = new BufferedImage(1200, 500, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.translate(300, 100);
		Dimension size = compass(config).render(graphics);
		graphics.dispose();
		int[] painted = paintedColumns(image);
		assertTrue("painted from x=" + painted[0], painted[0] >= 300);
		assertTrue("label painted to x=" + painted[1] + ", overlay ends at " + (300 + size.width),
			painted[1] < 300 + size.width);
		assertTrue("label painted to y=" + painted[2] + ", overlay ends at " + (100 + size.height),
			painted[2] < 100 + size.height);
	}

	@Test
	public void compassLabelStaysInsideTheReturnedBounds()
	{
		assertCompassStaysInsideItsBounds(DEFAULTS);
		assertCompassStaysInsideItsBounds(new HcimGuideConfig()
		{
			@Override
			public int overlayFontSize()
			{
				return 40;
			}
		});
	}

	@Test
	public void compassSizeSettingIsClampedToItsRange()
	{
		HcimGuideConfig wild = new HcimGuideConfig()
		{
			@Override
			public int compassSize()
			{
				return 5000;
			}

			@Override
			public boolean compassShowDistance()
			{
				return false;
			}
		};
		Graphics2D graphics = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB).createGraphics();
		assertEquals(new Dimension(96, 96), compass(wild).render(graphics));
		graphics.dispose();
	}

	// ------------------------------------------------------------------ guide screenshots

	private static final String SHOT = "https://i.ibb.co/Abc1234/image.png";

	/** Records screenshot traffic instead of touching a loader or a browser. */
	private static class ShotStub extends Stub
	{
		final List<String> opened = new ArrayList<>();
		final List<String> requested = new ArrayList<>();
		final List<java.util.function.Consumer<BufferedImage>> callbacks = new ArrayList<>();
		BufferedImage inMemory;

		@Override
		void openImageUrl(String url)
		{
			opened.add(url);
		}

		@Override
		BufferedImage cachedGuideImage(String url, int thumbWidth)
		{
			return inMemory;
		}

		@Override
		void loadGuideImage(String url, int thumbWidth, java.util.function.Consumer<BufferedImage> onDone)
		{
			requested.add(thumbWidth + " " + url);
			callbacks.add(onDone);
		}
	}

	private static final HcimGuideConfig SHOTS_ON = new HcimGuideConfig()
	{
		@Override
		public boolean loadGuideScreenshots()
		{
			return true;
		}
	};

	private static JLabel picture(Component root)
	{
		for (JLabel label : all(root, JLabel.class))
		{
			if (label.getIcon() instanceof javax.swing.ImageIcon)
			{
				return label;
			}
		}
		return null;
	}

	private static JButton button(Component root, String text)
	{
		for (JButton b : all(root, JButton.class))
		{
			if (text.equals(b.getText()))
			{
				return b;
			}
		}
		return null;
	}

	private static Guide shotGuide(ShotStub plugin)
	{
		Guide guide = guide(plugin, 2, 3, "Bank ", false);
		guide.getEpisodes().get(0).getBanks().get(0).addImageUrl(SHOT);
		return guide;
	}

	@Test
	public void screenshotIsOnlyABrowserLinkByDefault() throws Exception
	{
		edt(() ->
		{
			ShotStub plugin = new ShotStub();
			HcimGuidePanel panel = panel(plugin, DEFAULTS, shotGuide(plugin));
			Container open = section(panel, 0);
			assertTrue(expanded(open));
			// the link sits above the steps, where the wiki shows the picture
			JButton link = button(body(open).getComponent(0), "Guide screenshot");
			assertTrue(link != null);
			assertEquals(4, builtRows(open));
			link.doClick();
			assertEquals(Arrays.asList(SHOT), plugin.opened);
			// nothing is downloaded unless the setting is on
			assertTrue(plugin.requested.isEmpty());
			assertNull(picture(panel));
			// a section without a screenshot gets no link
			click(header(section(panel, 1)));
			assertNull(button(body(section(panel, 1)), "Guide screenshot"));
		});
	}

	@Test
	public void screenshotReplacesItsLinkOnceLoadedAndFitsThePanel() throws Exception
	{
		edt(() ->
		{
			ShotStub plugin = new ShotStub();
			HcimGuidePanel panel = panel(plugin, SHOTS_ON, shotGuide(plugin));
			Container open = section(panel, 0);
			assertEquals(Arrays.asList(PanelLayout.screenshotWidth() + " " + SHOT), plugin.requested);
			assertTrue("link stays until the picture arrives", button(open, "Guide screenshot") != null);
			int before = open.getPreferredSize().height;

			plugin.callbacks.get(0).accept(new BufferedImage(PanelLayout.screenshotWidth(), 120, BufferedImage.TYPE_INT_ARGB));
			settle(panel);
			JLabel shown = picture(open);
			assertTrue(shown != null);
			assertNull(button(open, "Guide screenshot"));
			assertTrue("section grew to hold the picture", open.getPreferredSize().height >= before + 100);
			assertTrue("picture wider than the section", shown.getPreferredSize().width <= open.getWidth());
			assertEquals(4, builtRows(open));
			// clicking the picture opens the full-size image
			MouseEvent click = new MouseEvent(shown, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1);
			for (MouseListener listener : shown.getMouseListeners())
			{
				listener.mouseClicked(click);
			}
			assertEquals(Arrays.asList(SHOT), plugin.opened);
		});
	}

	@Test
	public void lateOrFailedScreenshotNeverLandsInAClosedOrRebuiltSection() throws Exception
	{
		edt(() ->
		{
			ShotStub plugin = new ShotStub();
			HcimGuidePanel panel = panel(plugin, SHOTS_ON, shotGuide(plugin));
			Container open = section(panel, 0);
			// a failed download leaves the link in place
			plugin.callbacks.get(0).accept(null);
			assertTrue(button(open, "Guide screenshot") != null);

			click(header(open));
			assertFalse(expanded(open));
			click(header(open));
			settle(panel);
			assertEquals(2, plugin.callbacks.size());
			// the first open's download finishing now belongs to discarded rows
			plugin.callbacks.get(0).accept(new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB));
			assertNull(picture(panel));
			click(header(open));
			plugin.callbacks.get(1).accept(new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB));
			assertNull(picture(panel));
			assertEquals(0, builtRows(open));
		});
	}

	@Test
	public void screenshotAlreadyInMemoryShowsWithoutARequest() throws Exception
	{
		edt(() ->
		{
			ShotStub plugin = new ShotStub();
			plugin.inMemory = new BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB);
			HcimGuidePanel panel = panel(plugin, SHOTS_ON, shotGuide(plugin));
			assertTrue(picture(section(panel, 0)) != null);
			assertTrue(plugin.requested.isEmpty());
		});
	}
}
