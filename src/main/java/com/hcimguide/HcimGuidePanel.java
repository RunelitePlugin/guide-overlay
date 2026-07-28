package com.hcimguide;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

public class HcimGuidePanel extends PluginPanel
{
	private static final String EXPANDED = "▾"; // ▾
	private static final String COLLAPSED = "▸"; // ▸

	private final HcimGuidePlugin plugin;
	private final HcimGuideConfig config;

	private final JLabel statusLabel = new JLabel(" ");
	private final JLabel targetLabel = new JLabel(" ");
	private final JProgressBar overallProgress = new JProgressBar();
	private final JTextField searchField = new JTextField();
	/** One pending filter run per typing burst; restart() supersedes the last. */
	private final javax.swing.Timer searchDebounce =
		new javax.swing.Timer(200, e -> applyFilter());
	/**
	 * Matching steps beyond this many are shown as visible-but-collapsed
	 * sections instead of force-built rows: a broad query over a 2,000+ step
	 * guide must not construct thousands of Swing rows in one EDT pass.
	 */
	private static final int SEARCH_EXPAND_BUDGET = 400;
	/** True while the status line shows this filter's own broad-search note. */
	private boolean searchStatusShown;
	private final JComboBox<GuideRegistry.Entry> guideBox = new JComboBox<>();
	/** Jump-to-bank dropdown: every bank in the guide, flat - no episode grouping. */
	private final JComboBox<GuideBank> sectionBox = new JComboBox<>();
	private final JPanel banksContainer = new JPanel(new GridBagLayout());

	private Guide guide;
	private final List<BankSection> bankSections = new ArrayList<>();
	/** Optional "episode video guide" link rows; hidden while a search filter is active. */
	private final List<JPanel> episodeVideoRows = new ArrayList<>();
	private boolean rebuilding;
	/** The checklist scroll pane; centering jumps position its viewport directly. */
	private JScrollPane checklistScroll;

	/**
	 * Same fixed-width trick as RuneLite's own settings panel: the content
	 * always lays out at {@code PluginPanel.PANEL_WIDTH}, and the panel's
	 * outer width ({@code PANEL_WIDTH + SCROLLBAR_WIDTH}) leaves the vertical
	 * scrollbar its own dedicated column - the bar can never sit on top of
	 * content, so nothing needs to reserve or subtract its width.
	 */
	private static class FixedWidthPanel extends JPanel
	{
		@Override
		public java.awt.Dimension getPreferredSize()
		{
			return new java.awt.Dimension(net.runelite.client.ui.PluginPanel.PANEL_WIDTH,
				super.getPreferredSize().height);
		}
	}

	HcimGuidePanel(HcimGuidePlugin plugin, HcimGuideConfig config)
	{
		super(false);
		this.plugin = plugin;
		this.config = config;
		searchDebounce.setRepeats(false);

		setLayout(new BorderLayout(0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// no outer side padding: margins live on the header and the scrollable
		// content (RuneLite settings-panel pattern), so the scroll pane spans
		// the full panel and the scrollbar gets its own column at the edge
		setBorder(BorderFactory.createEmptyBorder());

		// ---------------- header ----------------
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(8, PanelLayout.CONTENT_MARGIN_LEFT,
			0, PanelLayout.CONTENT_MARGIN_RIGHT));

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Guide Overlay");
		title.setForeground(Color.WHITE);
		titleRow.add(title, BorderLayout.WEST);

		JButton menuButton = new JButton("⋮");
		menuButton.setToolTipText("Guide import options");
		menuButton.setMargin(new Insets(0, 6, 0, 6));
		menuButton.setFocusPainted(false);
		JPopupMenu importMenu = buildImportMenu();
		menuButton.addActionListener(e -> importMenu.show(menuButton, 0, menuButton.getHeight()));
		titleRow.add(menuButton, BorderLayout.EAST);
		header.add(titleRow);

		header.add(Box.createVerticalStrut(4));

		guideBox.setRenderer(new GuideRenderer());
		guideBox.addActionListener(e ->
		{
			if (!rebuilding)
			{
				GuideRegistry.Entry entry = (GuideRegistry.Entry) guideBox.getSelectedItem();
				if (entry != null && !entry.getId().equals(plugin.getCurrentGuideId()))
				{
					plugin.selectGuide(entry.getId());
				}
			}
		});
		header.add(guideBox);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(statusLabel);

		targetLabel.setForeground(ColorScheme.BRAND_ORANGE);
		header.add(targetLabel);

		overallProgress.setStringPainted(true);
		overallProgress.setForeground(new Color(0, 146, 84));
		overallProgress.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		overallProgress.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		header.add(overallProgress);

		header.add(Box.createVerticalStrut(4));

		searchField.setToolTipText("Search steps");
		// debounced: each keystroke restarts the timer, so only the final
		// state of a fast-typed query pays the filter/row-build cost
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}
		});
		header.add(searchField);

		header.add(Box.createVerticalStrut(4));

		sectionBox.setRenderer(new SectionRenderer());
		sectionBox.setToolTipText("Jump to a bank section");
		sectionBox.addActionListener(e ->
		{
			if (!rebuilding)
			{
				GuideBank bank = (GuideBank) sectionBox.getSelectedItem();
				if (bank != null)
				{
					scrollToBank(bank.getId());
				}
			}
		});
		header.add(sectionBox);

		header.add(Box.createVerticalStrut(4));

		JButton resume = new JButton("Jump to next unchecked step");
		resume.setFocusPainted(false);
		resume.addActionListener(e -> jumpToNextUnchecked());
		resume.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(resume);

		header.add(Box.createVerticalStrut(6));

		add(header, BorderLayout.NORTH);

		// ---------------- bank list ----------------
		// RuneLite ConfigPanel's own working container pattern: a fixed-width
		// content panel with the settings panel's standard margins, inside a
		// fixed-width north-aligned wrapper, inside a plain scroll pane whose
		// vertical bar appears only when needed - in its own column, never
		// over the content
		banksContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel content = new FixedWidthPanel();
		content.setLayout(new BorderLayout());
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(PanelLayout.CONTENT_MARGIN_TOP,
			PanelLayout.CONTENT_MARGIN_LEFT, PanelLayout.CONTENT_MARGIN_BOTTOM,
			PanelLayout.CONTENT_MARGIN_RIGHT));
		content.add(banksContainer, BorderLayout.CENTER);

		JPanel wrapper = new FixedWidthPanel();
		wrapper.setLayout(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(content, BorderLayout.NORTH);

		checklistScroll = new JScrollPane(wrapper);
		checklistScroll.setBorder(null);
		checklistScroll.getVerticalScrollBar().setUnitIncrement(16);
		checklistScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(checklistScroll, BorderLayout.CENTER);

		applyPanelFont();
		setStatus("Loading guide...");
	}

	/** The one configured side-panel font, shared by every component. */
	private java.awt.Font panelFont()
	{
		// CLIENT_DEFAULT fallback: the regular RuneScape face, matching what
		// baseline step text used
		return PanelFonts.resolve(FontManager.getRunescapeFont(), config);
	}

	/**
	 * Apply the configured side-panel font to EVERY text-bearing component the
	 * panel owns - title, buttons, selectors, status lines, progress bar,
	 * search field, headers, rows, links, badges - so a font change never
	 * leaves a component behind on a hard-coded font.
	 */
	private void applyPanelFont()
	{
		applyFontRecursively(this, panelFont());
		repaint();
	}

	private static void applyFontRecursively(Component component, java.awt.Font font)
	{
		if (component instanceof ItemGridPanel)
		{
			// the grid's fixed 36x32 slots size their own quantity/fallback
			// text to fit; the panel font would overflow the slot bounds
			return;
		}
		component.setFont(font);
		if (component instanceof java.awt.Container)
		{
			for (Component child : ((java.awt.Container) component).getComponents())
			{
				applyFontRecursively(child, font);
			}
		}
	}

	/**
	 * The guide is imported once and stored locally; these menu actions are the
	 * only way any content ever changes. Both import actions require explicit
	 * confirmation, and the previous snapshot can always be restored.
	 */
	private JPopupMenu buildImportMenu()
	{
		JPopupMenu menu = new JPopupMenu();

		// Diagnostics and data plumbing live in their own submenu. They are only
		// useful when investigating a problem, and in the main menu they crowded
		// out the actions players actually reach for.
		JMenu devTools = new JMenu("Developer tools");
		devTools.setToolTipText("Diagnostics and data export. Not needed for normal play.");

		JMenuItem addFromLink = new JMenuItem("Add guide from wiki link...");
		addFromLink.addActionListener(e ->
		{
			String link = JOptionPane.showInputDialog(this,
				"Paste an oldschool.runescape.wiki guide link:\n"
					+ "(e.g. https://oldschool.runescape.wiki/w/Guide:Some_Guide)",
				"Add guide", JOptionPane.PLAIN_MESSAGE);
			if (link != null && !link.trim().isEmpty())
			{
				plugin.addGuideFromLink(link);
			}
		});
		menu.add(addFromLink);

		JMenuItem addFromFile = new JMenuItem("Add guide from file...");
		addFromFile.addActionListener(e ->
		{
			String name = JOptionPane.showInputDialog(this,
				"Name for the new guide (shown in the dropdown):",
				"Add guide from file", JOptionPane.PLAIN_MESSAGE);
			if (name == null || name.trim().isEmpty())
			{
				return;
			}
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle("Choose the guide's wikitext file");
			if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
			{
				plugin.addGuideFromFile(name.trim(), chooser.getSelectedFile());
			}
		});
		menu.add(addFromFile);

		JMenuItem fromWiki = new JMenuItem("Re-import selected guide from wiki...");
		fromWiki.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"This downloads the selected guide from the OSRS wiki ONCE and stores it locally.\n"
					+ "If it is already stored, the stored copy is replaced (a backup is kept).\n"
					+ "Steps whose wording changed on the wiki will reset to unchecked.\n\nContinue?",
				"Import guide from wiki", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.OK_OPTION)
			{
				plugin.importSelectedFromWiki();
			}
		});
		menu.add(fromWiki);

		JMenuItem fromFile = new JMenuItem("Import selected guide from file...");
		fromFile.addActionListener(e ->
		{
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle("Choose a guide wikitext file");
			if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
			{
				plugin.importFromFile(chooser.getSelectedFile());
			}
		});
		menu.add(fromFile);

		JMenuItem restore = new JMenuItem("Restore previous import");
		restore.addActionListener(e -> plugin.restorePreviousImport());
		menu.add(restore);

		JMenuItem removeGuide = new JMenuItem("Remove selected guide from list");
		removeGuide.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Remove this guide from the dropdown? Its progress and stored\n"
					+ "snapshot stay on disk in case you re-add it later.",
				"Remove guide", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.OK_OPTION)
			{
				plugin.removeSelectedGuide();
			}
		});
		menu.add(removeGuide);

		menu.addSeparator();

		JMenuItem syncAccount = new JMenuItem("Sync checklist to my account...");
		syncAccount.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Check off every quest and skill step your account has already\n"
					+ "completed - across the WHOLE guide? Item steps are left alone\n"
					+ "(inventories change). Undo afterwards with 'Undo last bulk\n"
					+ "change' if it marks more than you wanted.",
				"Sync checklist to account", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (choice == JOptionPane.OK_OPTION)
			{
				plugin.syncToAccount();
			}
		});
		menu.add(syncAccount);

		JMenuItem undoBulk = new JMenuItem("Undo last bulk change");
		undoBulk.addActionListener(e ->
		{
			if (plugin.undoLastBulk())
			{
				rebuildBanks();
			}
		});
		menu.add(undoBulk);

		JMenuItem exportProgress = new JMenuItem("Export progress to clipboard");
		exportProgress.addActionListener(e -> plugin.exportProgress());
		menu.add(exportProgress);

		JMenuItem fullDb = new JMenuItem("Download full location database (one time)...");
		fullDb.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Download the full-game NPC location database? This is a ONE-TIME\n"
					+ "download (a few MB) from the community dataset behind the OSRS\n"
					+ "wiki's interactive map (mejrs/data_osrs). It fills every gap so the\n"
					+ "compass can point at any named NPC - positions the plugin has\n"
					+ "already observed are always kept.\n\nContinue?",
				"Download full location database", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (choice == JOptionPane.OK_OPTION)
			{
				plugin.downloadFullLocationDb();
			}
		});
		menu.add(fullDb);

		JMenuItem exportLocations = new JMenuItem("Export NPC locations to clipboard");
		exportLocations.addActionListener(e -> plugin.exportLocations());
		devTools.add(exportLocations);

		JMenuItem importLocations = new JMenuItem("Import NPC locations from clipboard...");
		importLocations.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Merge NPC locations from the JSON on your clipboard into the local\n"
					+ "database? Imported entries overwrite existing ones (positions the\n"
					+ "plugin observes in-game afterwards will keep self-correcting).",
				"Import NPC locations", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (choice != JOptionPane.OK_OPTION)
			{
				return;
			}
			// clipboard is read off the EDT - a stalled clipboard owner (X11)
			// must never freeze the client UI
			setStatus("Reading clipboard…");
			plugin.readClipboardText(json ->
			{
				if (json == null)
				{
					SwingUtilities.invokeLater(() -> setStatus("Clipboard does not contain text"));
					return;
				}
				plugin.importLocations(json);
			});
		});
		devTools.add(importLocations);

		menu.addSeparator();

		JMenuItem setTilePin = new JMenuItem("Set current tile as step destination");
		setTilePin.addActionListener(e -> plugin.setCurrentTileAsCustomPin(false));
		menu.add(setTilePin);

		JMenuItem addTileWaypoint = new JMenuItem("Add current tile as waypoint");
		addTileWaypoint.addActionListener(e -> plugin.setCurrentTileAsCustomPin(true));
		menu.add(addTileWaypoint);

		JMenuItem setMapPin = new JMenuItem("Set world-map center as destination");
		setMapPin.addActionListener(e -> plugin.setWorldMapCenterAsCustomPin(false));
		menu.add(setMapPin);

		JMenuItem addMapWaypoint = new JMenuItem("Add world-map center as waypoint");
		addMapWaypoint.addActionListener(e -> plugin.setWorldMapCenterAsCustomPin(true));
		menu.add(addMapWaypoint);

		JMenuItem renameWaypoint = new JMenuItem("Rename active custom waypoint...");
		renameWaypoint.addActionListener(e -> plugin.renameActiveCustomWaypoint());
		menu.add(renameWaypoint);

		JMenuItem moveWaypointEarlier = new JMenuItem("Move active custom waypoint earlier");
		moveWaypointEarlier.addActionListener(e -> plugin.moveActiveCustomWaypoint(-1));
		menu.add(moveWaypointEarlier);

		JMenuItem moveWaypointLater = new JMenuItem("Move active custom waypoint later");
		moveWaypointLater.addActionListener(e -> plugin.moveActiveCustomWaypoint(1));
		menu.add(moveWaypointLater);

		JMenuItem removeWaypoint = new JMenuItem("Remove active custom waypoint");
		removeWaypoint.addActionListener(e -> plugin.removeActiveCustomWaypoint());
		menu.add(removeWaypoint);

		JMenuItem clearPin = new JMenuItem("Restore automatic destination for step");
		clearPin.addActionListener(e -> plugin.clearCustomPinForCurrentStep());
		menu.add(clearPin);

		JMenuItem resetDamaged = new JMenuItem("Reset damaged custom pins...");
		resetDamaged.setToolTipText("Only needed if pin saves fail because the stored data is unreadable");
		resetDamaged.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Discard the stored custom pin data?\n\n"
					+ "Only do this if pin saves are failing because the stored\n"
					+ "data is unreadable. All custom pins will be lost.",
				"Reset damaged custom pins", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.OK_OPTION)
			{
				plugin.resetDamagedPinStore();
			}
		});
		devTools.add(resetDamaged);

		JMenuItem snoozeLocation = new JMenuItem("Snooze all location guidance for 5 minutes");
		snoozeLocation.addActionListener(e -> plugin.snoozeLocationGuide());
		menu.add(snoozeLocation);

		JMenuItem restoreLocation = new JMenuItem("Restore all location guidance");
		restoreLocation.addActionListener(e -> plugin.restoreLocationGuide());
		menu.add(restoreLocation);

		JMenuItem exportCustom = new JMenuItem("Export custom pins to file...");
		exportCustom.addActionListener(e -> saveTextFile("custom-locations.json",
			plugin::exportCustomLocations));
		menu.add(exportCustom);

		JMenuItem importCustom = new JMenuItem("Import custom pins from clipboard...");
		importCustom.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Merge custom pins for the currently selected guide from the JSON on your clipboard?",
				"Import custom pins", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (choice != JOptionPane.OK_OPTION)
			{
				return;
			}
			setStatus("Reading clipboard…");
			plugin.readClipboardText(json ->
			{
				if (json == null)
				{
					SwingUtilities.invokeLater(() -> setStatus("Clipboard does not contain text"));
					return;
				}
				try
				{
					int imported = plugin.importCustomLocations(json);
					SwingUtilities.invokeLater(() -> setStatus("Imported " + imported + " custom step locations"));
				}
				catch (RuntimeException ex)
				{
					SwingUtilities.invokeLater(() -> setStatus("Custom pin import failed: " + ex.getMessage()));
				}
			});
		});
		menu.add(importCustom);

		JMenuItem exportAudit = new JMenuItem("Export unresolved location audit...");
		exportAudit.addActionListener(e -> saveTextFile("unresolved-location-audit.md",
			plugin::exportLocationAudit));
		devTools.add(exportAudit);

		JMenuItem preload = new JMenuItem("Preload all guide items");
		preload.setToolTipText(
			"Resolves every item in the guide up front. Run this before exporting the "
				+ "item audit, or the report will be incomplete.");
		preload.addActionListener(e -> plugin.preloadGuideItems());
		devTools.add(preload);

		JMenuItem exportItems = new JMenuItem("Export item resolution audit...");
		exportItems.setToolTipText(
			"Lists every requirement in the guide that shows as text instead of an item picture");
		exportItems.addActionListener(e -> saveTextFile("item-resolution-audit.md",
			plugin::exportItemAudit));
		devTools.add(exportItems);

		menu.addSeparator();
		menu.add(devTools);

		JMenuItem importProgress = new JMenuItem("Import progress from clipboard...");
		importProgress.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"This REPLACES this character's current progress with the progress\n"
					+ "code on your clipboard. Continue?",
				"Import progress", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.OK_OPTION)
			{
				return;
			}
			// clipboard only touched after the user confirms, and read off the
			// EDT so a stalled clipboard owner can't freeze the client UI
			setStatus("Reading clipboard…");
			plugin.readClipboardText(code ->
			{
				if (code == null)
				{
					SwingUtilities.invokeLater(() -> setStatus("Clipboard does not contain text"));
					return;
				}
				plugin.importProgress(code);
			});
		});
		menu.add(importProgress);

		return menu;
	}

	/**
	 * File chooser on the EDT (fast), then CONTENT GENERATION and the disk
	 * write on the plugin executor: the audit walks thousands of steps and
	 * the pin export serializes the whole store - neither may stall Swing,
	 * so the supplier is only ever evaluated off the EDT.
	 */
	private void saveTextFile(String suggestedName, java.util.function.Supplier<String> text)
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Save " + suggestedName);
		chooser.setSelectedFile(new java.io.File(suggestedName));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		java.io.File file = chooser.getSelectedFile();
		setStatus("Saving " + file.getName() + "…");
		plugin.runOffEdt(() ->
		{
			String content;
			try
			{
				content = text.get();
			}
			catch (RuntimeException ex)
			{
				SwingUtilities.invokeLater(() -> setStatus("Export failed: " + ex.getMessage()));
				return;
			}
			try
			{
				java.nio.file.Files.write(file.toPath(),
					content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				SwingUtilities.invokeLater(() -> setStatus("Saved " + file.getName()));
			}
			catch (java.io.IOException ex)
			{
				SwingUtilities.invokeLater(() -> setStatus("Save failed: " + ex.getMessage()));
			}
		});
	}

	// ---------------------------------------------------------------- API used by plugin

	/**
	 * Single cleanup point for anything the panel owns that could outlive it.
	 * Called from the plugin's shutDown before the panel reference is dropped,
	 * so a pending debounce cannot briefly retain the panel after disable.
	 */
	void dispose()
	{
		searchDebounce.stop();
		// detach every grid's async icon callbacks: an image that finishes
		// loading after disable must not resurrect (or retain) the dead tree
		disposeGrids();
	}

	private void disposeGrids()
	{
		for (BankSection section : bankSections)
		{
			for (StepRow row : section.rows)
			{
				if (row.grid != null)
				{
					row.grid.dispose();
				}
			}
		}
	}

	void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	/** Populate the guide dropdown (EDT only). */
	void setGuides(java.util.List<GuideRegistry.Entry> entries, String selectedId)
	{
		importedCache.clear();
		rebuilding = true;
		guideBox.removeAllItems();
		GuideRegistry.Entry toSelect = null;
		for (GuideRegistry.Entry e : entries)
		{
			guideBox.addItem(e);
			if (e.getId().equals(selectedId))
			{
				toSelect = e;
			}
		}
		if (toSelect != null)
		{
			guideBox.setSelectedItem(toSelect);
		}
		rebuilding = false;
	}

	/** Repaint dropdown labels after an import changes a guide's "(not imported)" state. */
	void refreshGuideListLabels()
	{
		importedCache.clear();
		guideBox.repaint();
	}

	/**
	 * Imported-state answers for the dropdown renderer. The underlying check
	 * is a disk stat, and a cell renderer runs per visible cell per paint -
	 * uncached, a slow home directory stutters the EDT every popup. Cleared
	 * whenever an import/removal could change an answer.
	 */
	private final java.util.Map<String, Boolean> importedCache = new java.util.HashMap<>();

	private boolean isGuideImportedCached(String guideId)
	{
		return importedCache.computeIfAbsent(guideId, plugin::isGuideImported);
	}

	/** Empty the panel when the selected guide has no snapshot yet (EDT only). */
	void clearGuide(String status)
	{
		guide = null;
		rebuilding = true;
		sectionBox.removeAllItems();
		rebuilding = false;
		disposeGrids(); // late icon loads must not touch the discarded rows
		banksContainer.removeAll();
		bankSections.clear();
		episodeVideoRows.clear();
		overallProgress.setValue(0);
		overallProgress.setString("no guide");
		setTargetStatus(null, false); // the old guide's pin no longer exists
		setStatus(status);
		banksContainer.revalidate();
		banksContainer.repaint();
	}

	/** One-click import offer for a guide that isn't stored locally yet (EDT only). */
	void offerImport(GuideRegistry.Entry entry)
	{
		int choice = JOptionPane.showConfirmDialog(this,
			"\"" + entry.getTitle() + "\" hasn't been imported yet.\n\n"
				+ "Download it from the OSRS wiki now? This is a ONE-TIME download -\n"
				+ "the guide is stored locally and never updated automatically.",
			"Import guide", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (choice == JOptionPane.OK_OPTION)
		{
			plugin.importSelectedFromWiki();
		}
	}

	/** One-time offer after the first guide import (EDT only). */
	void offerFullDbDownload()
	{
		int choice = JOptionPane.showConfirmDialog(this,
			"Also download the full-game NPC location database now?\n\n"
				+ "One-time download (a few MB) from the community dataset behind the\n"
				+ "OSRS wiki's interactive map - it lets the compass point at any named\n"
				+ "NPC, not just ones you've already seen. You can do this later from\n"
				+ "the ⋮ menu.",
			"Full location database", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (choice == JOptionPane.OK_OPTION)
		{
			plugin.downloadFullLocationDb();
		}
	}

	private class GuideRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
		{
			Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			setFont(panelFont());
			if (value instanceof GuideRegistry.Entry)
			{
				GuideRegistry.Entry e = (GuideRegistry.Entry) value;
				String suffix = isGuideImportedCached(e.getId()) ? "" : "  (not imported)";
				setText(e.getTitle() + suffix);
			}
			return c;
		}
	}

	/** Re-sync every visible row/checkbox from the plugin's model (EDT only). */
	void refreshFromModel()
	{
		for (BankSection section : bankSections)
		{
			for (StepRow row : section.rows)
			{
				row.syncFromModel();
			}
			// headers are refreshed by updateProgress() below - no duplicate pass
			if (config.autoCollapseCompleted() && section.isComplete() && section.expanded
				&& !section.bank.getSteps().isEmpty())
			{
				section.setExpanded(false);
				expandNextAfterAutoCollapse(section);
			}
		}
		updateProgress();
		refreshAllPinButtons();
	}

	/** Called after the plugin auto-advances the pinned step. */
	void onPinChanged(String newTargetName)
	{
		refreshAllPinButtons();
		// snapshot, not the live computation - this runs on the EDT and the
		// live path walks client-thread state
		String summary = plugin.getActiveLocationSummarySnapshot();
		// the waypoint counter is exposed separately so the overlay can colour
		// it; the sidebar has no per-fragment colour here, so append it plainly
		String waypoint = plugin.getWaypointStatusSnapshot();
		if (summary != null && waypoint != null)
		{
			summary = summary + " · " + waypoint;
		}
		setTargetStatus(summary != null ? summary : newTargetName, false);
	}

	/** Re-create rows so config toggles (item grids, dimming, fonts) apply immediately. */
	void onConfigChanged()
	{
		// rebuildBanks re-applies the configured font to the whole panel tree
		rebuildBanks();
	}

	/** Push a fresh inventory snapshot into all visible item grids (EDT only). */
	void updateInventory(InventorySnapshot snapshot)
	{
		for (BankSection section : bankSections)
		{
			for (StepRow row : section.rows)
			{
				if (row.grid != null)
				{
					row.grid.updatePresence(snapshot);
				}
			}
		}
	}

	/**
	 * Re-run icon resolution for every built grid (EDT only) - called after
	 * the full item-database scan finds icons the price search couldn't.
	 */
	void reresolveIcons()
	{
		for (BankSection section : bankSections)
		{
			for (StepRow row : section.rows)
			{
				if (row.grid != null)
				{
					plugin.resolveItemIcons(row.grid);
				}
			}
		}
	}

	void setTargetStatus(String name, boolean nearby)
	{
		if (name == null)
		{
			targetLabel.setText(" ");
		}
		else
		{
			targetLabel.setText("Tracking: " + name + (nearby ? " (nearby!)" : " (not nearby)"));
		}
	}

	void setGuide(Guide guide, String status)
	{
		this.guide = guide;
		// guide switched: the old pin was cleared (selectGuideInternal pins
		// null first), so drop the stale "Tracking: ..." label. A same-guide
		// re-import keeps its pin, so keep the label then.
		if (plugin.getPinnedStepKey() == null)
		{
			setTargetStatus(null, false);
		}
		setStatus(status);

		rebuilding = true;
		GuideBank selected = (GuideBank) sectionBox.getSelectedItem();
		String selectedId = selected != null ? selected.getId() : null;
		sectionBox.removeAllItems();
		GuideBank toSelect = null;
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				sectionBox.addItem(bank);
				if (bank.getId().equals(selectedId))
				{
					toSelect = bank;
				}
			}
		}
		if (toSelect == null)
		{
			// default the dropdown to the bank you're actually working on,
			// matching the auto-expanded section below
			String activeId = plugin.getActiveBankId();
			for (int i = 0; i < sectionBox.getItemCount() && toSelect == null; i++)
			{
				if (sectionBox.getItemAt(i).getId().equals(activeId))
				{
					toSelect = sectionBox.getItemAt(i);
				}
			}
		}
		if (toSelect != null)
		{
			sectionBox.setSelectedItem(toSelect);
		}
		else if (sectionBox.getItemCount() > 0)
		{
			sectionBox.setSelectedIndex(0);
		}
		rebuilding = false;

		rebuildBanks();
	}

	// ---------------------------------------------------------------- internals

	private void rebuildBanks()
	{
		// detach async icon callbacks of the old generation so discarded
		// component trees can't be resurrected by late image loads
		disposeGrids();
		banksContainer.removeAll();
		bankSections.clear();
		episodeVideoRows.clear();

		// every bank in the whole guide, flat - collapsed section headers are
		// cheap, rows only build on first expand
		if (guide != null)
		{
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.weightx = 1;
			gbc.fill = GridBagConstraints.HORIZONTAL;
			gbc.insets = new Insets(0, 0, 6, 0);

			String activeId = plugin.getActiveBankId();
			for (GuideEpisode ep : guide.getEpisodes())
			{
				// optional clickable episode video guide link, shown at the
				// start of the episode's banks (only when the guide has one)
				if (ep.getVideoUrl() != null)
				{
					JPanel row = buildEpisodeVideoRow(ep);
					episodeVideoRows.add(row);
					banksContainer.add(row, gbc);
					gbc.gridy++;
				}
				for (GuideBank bank : ep.getBanks())
				{
					BankSection section = new BankSection(bank);
					bankSections.add(section);
					banksContainer.add(section, gbc);
					gbc.gridy++;
					// only the bank you're actually working on opens (and builds) by default
					if (bank.getId().equals(activeId))
					{
						section.setExpanded(true);
					}
				}
			}
		}

		applyFilter();
		updateProgress();
		applyPanelFont();
		banksContainer.revalidate();
		banksContainer.repaint();
	}

	/**
	 * After a section auto-collapses on completion, open the next section
	 * that still has work and scroll to it - the checklist flows straight
	 * into the next bank with no extra clicks. Called only at the moment an
	 * AUTO-collapse happens, so sections the player collapsed by hand are
	 * never reopened.
	 */
	private void expandNextAfterAutoCollapse(BankSection collapsed)
	{
		// while a search filter is active, sections are hidden/shown by match:
		// expanding (and scrolling to) a filtered-out section would fight the
		// filter and scroll against stale layout - skip until the search clears
		if (!searchField.getText().trim().isEmpty())
		{
			return;
		}
		int from = bankSections.indexOf(collapsed);
		if (from < 0)
		{
			return;
		}
		for (int i = from + 1; i < bankSections.size(); i++)
		{
			BankSection next = bankSections.get(i);
			if (!next.isComplete())
			{
				if (!next.expanded)
				{
					next.setExpanded(true);
				}
				// scroll after the collapse/expand relayout settles
				SwingUtilities.invokeLater(() -> next.scrollRectToVisible(
					new Rectangle(0, 0, next.getWidth(), next.getHeight())));
				return;
			}
		}
	}

	/** Expand and scroll to one bank section; collapses the others. */
	private void scrollToBank(String bankId)
	{
		for (BankSection section : bankSections)
		{
			boolean isTarget = section.bank.getId().equals(bankId);
			section.setExpanded(isTarget);
			if (isTarget)
			{
				// scroll after the collapse/expand relayout settles
				SwingUtilities.invokeLater(() -> section.scrollRectToVisible(
					new Rectangle(0, 0, section.getWidth(), section.getHeight())));
			}
		}
	}

	/**
	 * Expand the bank that holds the given step and CENTER that step's row in
	 * the checklist viewport - not just the minimum scroll that makes it
	 * visible. Expanding lazily builds the rows, so the centering is deferred
	 * until the relayout settles. Falls back to the bank header if the row
	 * cannot be located.
	 */
	private void scrollToStep(String bankId, String stepKey)
	{
		for (BankSection section : bankSections)
		{
			boolean isTarget = section.bank.getId().equals(bankId);
			section.setExpanded(isTarget);
			if (!isTarget)
			{
				continue;
			}
			SwingUtilities.invokeLater(() ->
			{
				StepRow target = null;
				for (StepRow row : section.rows)
				{
					if (row.step.getKey().equals(stepKey))
					{
						target = row;
						break;
					}
				}
				if (target != null)
				{
					final StepRow r = target;
					// a second defer: the row's own bounds are only valid once
					// the section body has laid out its freshly built children
					SwingUtilities.invokeLater(() -> centerRowInViewport(r));
				}
				else
				{
					section.scrollRectToVisible(
						new Rectangle(0, 0, section.getWidth(), section.getHeight()));
				}
			});
		}
	}

	/**
	 * Place a step row's vertical center at the checklist viewport's center,
	 * clamped at both ends of the scrollable range; a row taller than the
	 * viewport aligns its top instead so its start is always readable.
	 * Scrolling only - never any completion, pin, phase, or waypoint change.
	 */
	private void centerRowInViewport(StepRow row)
	{
		// Freshly built rows reflow once their wrap labels learn their real
		// width, and those resize events arrive asynchronously - so center,
		// then re-center after the queue drains, until the position is stable
		// (bounded, so an endlessly-animating layout can't loop forever).
		centerRowInViewport(row, 3);
	}

	private void centerRowInViewport(StepRow row, int settlePasses)
	{
		javax.swing.JViewport viewport = checklistScroll.getViewport();
		Component view = viewport.getView();
		if (view == null || row.getParent() == null || !row.isShowing())
		{
			return;
		}
		// finish any reflow already requested (wrap-height second pass) so the
		// bounds being centered on are the settled ones
		checklistScroll.validate();
		Rectangle bounds = SwingUtilities.convertRectangle(
			row.getParent(), row.getBounds(), view);
		int y = PanelLayout.centeredViewPosition(bounds.y, bounds.height,
			viewport.getExtentSize().height, view.getHeight());
		boolean moved = y != viewport.getViewPosition().y;
		viewport.setViewPosition(new java.awt.Point(viewport.getViewPosition().x, y));
		if (settlePasses > 0 && (moved || settlePasses == 3))
		{
			// wrap-height corrections may still be queued behind this pass
			SwingUtilities.invokeLater(() -> centerRowInViewport(row, settlePasses - 1));
		}
	}

	private void applyFilter()
	{
		String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
		// with the whole guide flat in one list, a 1-char query would match
		// (and force-build) nearly every section at once - require 2+ chars
		if (q.length() == 1)
		{
			q = "";
		}
		// row-building budget: matching sections beyond it stay visible but
		// collapsed (expandable by hand), so one broad query can't stall the
		// EDT constructing every row in the guide
		int budget = SEARCH_EXPAND_BUDGET;
		boolean collapsedSome = false;
		for (BankSection section : bankSections)
		{
			boolean expand = budget > 0;
			int matched = section.filter(q, expand);
			if (matched > 0)
			{
				if (expand)
				{
					// expansion builds EVERY row of the section, matching or
					// not - charge the budget what it actually costs, or a
					// sparse query across many sections would build them all
					budget -= section.stepCount();
				}
				else
				{
					collapsedSome = true;
				}
			}
		}
		if (!q.isEmpty() && collapsedSome)
		{
			setStatus("Broad search - later matching sections are shown collapsed");
			searchStatusShown = true;
		}
		else if (searchStatusShown)
		{
			// reclaim only a status this filter itself put up - anything else
			// showing (import warnings etc.) stays
			setStatus(" ");
			searchStatusShown = false;
		}
		// episode video rows have no steps to match - hide them while filtering
		for (JPanel row : episodeVideoRows)
		{
			row.setVisible(q.isEmpty());
		}
		banksContainer.revalidate();
		banksContainer.repaint();
	}

	/** Slim link-styled row that opens an episode's video guide in the browser. */
	private JPanel buildEpisodeVideoRow(GuideEpisode ep)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton link = new JButton("▶ Episode " + ep.getNumber() + " video guide");
		link.setToolTipText("Open in browser: " + ep.getVideoUrl());
		link.setHorizontalAlignment(JButton.LEFT);
		link.setBorderPainted(false);
		link.setContentAreaFilled(false);
		link.setFocusPainted(false);
		link.setForeground(ColorScheme.BRAND_ORANGE);
		link.setFont(panelFont());
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setMargin(new Insets(1, 4, 1, 4));
		link.addActionListener(e -> plugin.openVideoUrl(ep.getVideoUrl()));
		row.add(link, BorderLayout.WEST);
		return row;
	}

	void updateProgress()
	{
		if (guide == null)
		{
			return;
		}
		int total = 0;
		int done = 0;
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank b : ep.getBanks())
			{
				int[] p = plugin.progressOf(b.getSteps());
				done += p[0];
				total += p[1];
			}
		}
		overallProgress.setMaximum(Math.max(1, total));
		overallProgress.setValue(done);
		overallProgress.setString(done + " / " + total + " steps");

		for (BankSection section : bankSections)
		{
			section.updateHeader();
		}
		sectionBox.repaint();
	}

	private void jumpToNextUnchecked()
	{
		if (guide == null)
		{
			return;
		}
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					if (!plugin.isStepDone(step.getKey()))
					{
						// clear an active search NOW: setText only restarts the
						// 200ms debounce, and centering against a half-filtered
						// layout either finds the row hidden or centers on
						// bounds the deferred filter pass then shifts
						searchField.setText("");
						searchDebounce.stop();
						applyFilter();
						// keep the dropdown in sync without re-triggering a jump
						rebuilding = true;
						sectionBox.setSelectedItem(bank);
						rebuilding = false;
						final String stepKey = step.getKey();
						final String bankId = bank.getId();
						SwingUtilities.invokeLater(() -> scrollToStep(bankId, stepKey));
						return;
					}
				}
			}
		}
		setStatus("Everything is checked off. Gz!");
	}

	private class SectionRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
		{
			Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			setFont(panelFont());
			if (value instanceof GuideBank)
			{
				GuideBank bank = (GuideBank) value;
				int[] prog = plugin.progressOf(bank.getSteps());
				setText(shortTitle(bank.getTitle()) + "  (" + prog[0] + "/" + prog[1] + ")");
			}
			return c;
		}
	}

	// ---------------------------------------------------------------- bank section

	private class BankSection extends JPanel
	{
		private final GuideBank bank;
		private final JPanel body = new JPanel(new GridBagLayout());
		private final JLabel chevron = new JLabel(EXPANDED);
		private final JLabel headerTitle = new JLabel();
		private final JLabel headerCount = new JLabel();
		private final List<StepRow> rows = new ArrayList<>();
		private boolean expanded;
		/** Rows and item grids are created on FIRST expand, so a guide with
		 * hundreds of steps loads instantly - only the ~50 headers are built. */
		private boolean built;
		private final JPanel headerPanel;

		BankSection(GuideBank bank)
		{
			this.bank = bank;
			setLayout(new BorderLayout());
			setBackground(ColorScheme.DARKER_GRAY_COLOR);

			// header
			headerPanel = new JPanel(new BorderLayout(6, 0));
			headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			headerPanel.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
			headerPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			chevron.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			headerPanel.add(chevron, BorderLayout.WEST);

			headerTitle.setText(bank.getTitle());
			// no forced bold: the configured font governs; headers keep their
			// hierarchy through the white-vs-gray foreground below
			headerTitle.setFont(panelFont());
			headerTitle.setForeground(Color.WHITE);
			headerPanel.add(headerTitle, BorderLayout.CENTER);

			headerCount.setFont(panelFont());
			headerCount.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			JPanel headerEast = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
			headerEast.setOpaque(false);
			if (bank.getVideoUrl() != null)
			{
				JButton videoButton = new JButton("▶");
				videoButton.setToolTipText("Watch this section's video guide: " + bank.getVideoUrl());
				videoButton.setMargin(new Insets(0, 4, 0, 4));
				videoButton.setFocusPainted(false);
				// keep the header's right-click menu reachable over the button
				videoButton.setInheritsPopupMenu(true);
				videoButton.addActionListener(e -> plugin.openVideoUrl(bank.getVideoUrl()));
				headerEast.add(videoButton);
			}
			headerEast.add(headerCount);
			headerPanel.add(headerEast, BorderLayout.EAST);

			JPopupMenu menu = new JPopupMenu();
			JMenuItem markAll = new JMenuItem("Mark bank complete");
			markAll.addActionListener(e -> bulk(true));
			menu.add(markAll);
			JMenuItem clearAll = new JMenuItem("Clear bank");
			clearAll.addActionListener(e -> bulk(false));
			menu.add(clearAll);
			JMenuItem upToHere = new JMenuItem("Mark everything before this bank complete");
			upToHere.addActionListener(e -> markEverythingBefore());
			menu.add(upToHere);
			if (bank.getVideoUrl() != null)
			{
				JMenuItem watchVideo = new JMenuItem("Watch section video guide");
				watchVideo.setToolTipText(bank.getVideoUrl());
				watchVideo.addActionListener(e -> plugin.openVideoUrl(bank.getVideoUrl()));
				menu.add(watchVideo);
			}
			headerPanel.setComponentPopupMenu(menu);

			headerPanel.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e))
					{
						setExpanded(!expanded);
						// expanding an over-budget section mid-search builds
						// its rows with default (all-visible) state - re-apply
						// the filter so they match every other section
						if (expanded && !searchField.getText().trim().isEmpty())
						{
							applyFilter();
						}
					}
				}
			});

			add(headerPanel, BorderLayout.NORTH);

			// body - populated lazily on first expand
			body.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
			body.setVisible(false);
			chevron.setText(COLLAPSED);
			add(body, BorderLayout.CENTER);

			updateHeader();
		}

		/** Builds the step rows the first time the section is opened. */
		private void ensureBuilt()
		{
			if (built)
			{
				return;
			}
			built = true;
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.weightx = 1;
			gbc.fill = GridBagConstraints.HORIZONTAL;
			for (GuideStep step : bank.getSteps())
			{
				StepRow row = new StepRow(this, step);
				rows.add(row);
				body.add(row, gbc);
				gbc.gridy++;
			}
			// the section's video guide as a visible row under the steps, not
			// only the small header ▶ - in the wiki the video sits right under
			// the section's content, so this is where users look for it
			if (bank.getVideoUrl() != null)
			{
				JButton link = new JButton("▶ Section video guide");
				link.setToolTipText("Open in browser: " + bank.getVideoUrl());
				link.setHorizontalAlignment(JButton.LEFT);
				link.setBorderPainted(false);
				link.setContentAreaFilled(false);
				link.setFocusPainted(false);
				link.setForeground(ColorScheme.BRAND_ORANGE);
				link.setFont(panelFont());
				link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				link.addActionListener(e -> plugin.openVideoUrl(bank.getVideoUrl()));
				body.add(link, gbc);
			}
			// rows are built lazily, after the panel-wide font pass - bring
			// every component of this fresh section body onto the configured font
			applyFontRecursively(body, panelFont());
		}

		boolean isComplete()
		{
			// skipped steps count as "no longer needs doing"
			int[] p = plugin.progressOf(bank.getSteps());
			return p[0] == p[1];
		}

		void setExpanded(boolean value)
		{
			if (value)
			{
				ensureBuilt();
			}
			expanded = value;
			body.setVisible(value);
			chevron.setText(value ? EXPANDED : COLLAPSED);
			revalidate();
		}

		void updateHeader()
		{
			int[] p = plugin.progressOf(bank.getSteps());
			int done = p[0];
			int total = p[1];
			headerCount.setText(done + "/" + total);
			headerTitle.setForeground(done == total && total > 0 ? new Color(0, 200, 120) : Color.WHITE);

			boolean active = bank.getId().equals(plugin.getActiveBankId());
			headerPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, active ? 3 : 0, 0, 0, ColorScheme.BRAND_ORANGE),
				BorderFactory.createEmptyBorder(5, active ? 3 : 6, 5, 6)));
		}

		/** Rows an expansion would build - what filtering actually costs. */
		int stepCount()
		{
			return bank.getSteps().size();
		}

		/**
		 * Applies the search query and returns how many steps matched.
		 * Matching runs against the MODEL, so unbuilt (collapsed) sections
		 * are searched without constructing their rows; only when
		 * {@code expand} is true does a matching section actually build and
		 * show its rows - the panel budgets that across sections, leaving
		 * the tail of a very broad query visible but collapsed.
		 */
		int filter(String query, boolean expand)
		{
			if (query.isEmpty())
			{
				setVisible(true);
				for (StepRow row : rows)
				{
					row.setVisible(true);
				}
				return 0;
			}
			int matched = 0;
			for (GuideStep step : bank.getSteps())
			{
				if (step.getText().toLowerCase(Locale.ROOT).contains(query))
				{
					matched++;
				}
			}
			setVisible(matched > 0);
			if (matched == 0)
			{
				return 0;
			}
			if (expand)
			{
				setExpanded(true); // builds rows on demand
				for (StepRow row : rows)
				{
					row.setVisible(row.step.getText().toLowerCase(Locale.ROOT).contains(query));
				}
			}
			else if (expanded)
			{
				// over budget but the rows already exist: still filter them
				for (StepRow row : rows)
				{
					row.setVisible(row.step.getText().toLowerCase(Locale.ROOT).contains(query));
				}
			}
			return matched;
		}

		private void bulk(boolean completed)
		{
			plugin.snapshotBeforeBulk(completed ? "mark bank complete" : "mark bank incomplete");
			List<GuideStep> targets = new ArrayList<>();
			for (GuideStep step : bank.getSteps())
			{
				// skipped steps are excluded from progress - leave them alone
				if (!plugin.isSkipped(step.getKey()))
				{
					targets.add(step);
				}
			}
			plugin.setCompletedBulk(targets, completed);
			for (StepRow row : rows)
			{
				row.syncFromModel();
			}
			updateProgress();
			if (completed && config.autoCollapseCompleted())
			{
				setExpanded(false);
				expandNextAfterAutoCollapse(this);
			}
		}

		private void markEverythingBefore()
		{
			if (guide == null)
			{
				return;
			}
			List<GuideStep> steps = new ArrayList<>();
			boolean found = false;
			outer:
			for (GuideEpisode ep : guide.getEpisodes())
			{
				for (GuideBank b : ep.getBanks())
				{
					if (b.getId().equals(bank.getId()))
					{
						found = true;
						break outer;
					}
					for (GuideStep step : b.getSteps())
					{
						// skipped steps stay skipped
						if (!plugin.isSkipped(step.getKey()))
						{
							steps.add(step);
						}
					}
				}
			}
			if (!found)
			{
				// guide was replaced while the menu was open - never complete
				// the whole guide off a stale anchor
				setStatus("Guide changed - nothing was marked");
				return;
			}
			plugin.snapshotBeforeBulk("mark everything before this bank");
			plugin.setCompletedBulk(steps, true);
			rebuildBanks();
		}
	}

	// ---------------------------------------------------------------- step row

	/**
	 * A wrapping HTML label that is authoritative about its own geometry: the
	 * Swing HTML {@code View} is sized to the width the layout actually
	 * allocated, and the preferred height is computed from the view's own
	 * Y-axis span at that width. No CSS {@code width} styling, no
	 * reconstruction of the available width from panel constants - the two
	 * approaches that repeatedly wrapped at the wrong point and ran text
	 * under the scrollbar.
	 */
	private static final class WrappingTextLabel extends JLabel
	{
		@Override
		public void setBounds(int x, int y, int width, int height)
		{
			super.setBounds(x, y, width, height);
			javax.swing.text.View view =
				(javax.swing.text.View) getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
			if (view != null && width > 0)
			{
				java.awt.Insets in = getInsets();
				view.setSize(Math.max(1, width - in.left - in.right), 0);
			}
		}

		@Override
		public java.awt.Dimension getPreferredSize()
		{
			javax.swing.text.View view =
				(javax.swing.text.View) getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
			if (view == null)
			{
				return super.getPreferredSize();
			}
			java.awt.Insets in = getInsets();
			int width = getWidth();
			if (width <= 0)
			{
				// first pass, before any layout: a conservative estimate keeps
				// the initial height close; the real allocated width (via
				// setBounds and the row's resize listener) settles it
				width = PanelLayout.preLayoutTextWidth();
			}
			float span = Math.max(1, width - in.left - in.right);
			view.setSize(span, 0);
			int h = (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS));
			return new java.awt.Dimension(width, h + in.top + in.bottom);
		}
	}

	private class StepRow extends JPanel
	{
		private final GuideStep step;
		/** Icon-only selection control; the text lives in {@link #textLabel}. */
		private final JCheckBox checkBox = new JCheckBox();
		/**
		 * Dedicated wrapping text component. Splitting it from the checkbox
		 * lets BorderLayout hand it EXACTLY the width left after indentation,
		 * the checkbox and the east controls; the label itself wraps its HTML
		 * view to that allocated width.
		 */
		private final WrappingTextLabel textLabel = new WrappingTextLabel();
		private final BankSection section;
		private final JButton pinButton;
		private final JPanel eastColumn = new JPanel();
		private final ItemGridPanel grid;
		private int lastWrapWidth = -1;
		/** Last rendered state: -1 never, 0 plain, 1 completed, 2 skipped. */
		private int lastRenderedState = -1;

		StepRow(BankSection section, GuideStep step)
		{
			this.section = section;
			this.step = step;

			setLayout(new BorderLayout());
			setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
			// indentation capped so deeply nested steps (and their item grids)
			// can never overflow the fixed side-panel width
			setBorder(BorderFactory.createEmptyBorder(2, 6 + Math.min(step.getDepth(), 2) * 8, 2, 2));

			checkBox.setSelected(plugin.isCompleted(step.getKey()));
			checkBox.setBackground(getBackground());
			checkBox.setFocusPainted(false);
			checkBox.addActionListener(e ->
			{
				plugin.setCompleted(step.getKey(), checkBox.isSelected());
				lastRenderedState = -1; // force re-render
				refreshText();
				updateProgress(); // refreshes every bank header including ours
				if (checkBox.isSelected() && config.autoCollapseCompleted() && section.isComplete())
				{
					section.setExpanded(false);
					expandNextAfterAutoCollapse(section);
				}
			});

			// the checklist content already keeps RuneLite's standard 10px right
			// margin; this small internal gap tops it up to a 12px visible
			// clearance between the wrap boundary and the divider/scrollbar
			textLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0,
				PanelLayout.TEXT_RIGHT_SAFETY));
			textLabel.setVerticalAlignment(JLabel.TOP);
			// clicking the text toggles the row, as it did when the checkbox
			// owned the text
			textLabel.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					if (javax.swing.SwingUtilities.isLeftMouseButton(e) && checkBox.isEnabled())
					{
						checkBox.doClick();
					}
				}
			});

			JPanel content = new JPanel(new BorderLayout());
			content.setOpaque(false);
			// NORTH-anchored so the box aligns with the FIRST line of wrapped text
			JPanel checkHolder = new JPanel(new BorderLayout());
			checkHolder.setOpaque(false);
			checkHolder.add(checkBox, BorderLayout.NORTH);
			content.add(checkHolder, BorderLayout.WEST);
			content.add(textLabel, BorderLayout.CENTER);
			add(content, BorderLayout.CENTER);

			// catch-up on ANY step of ANY guide, even ones without bank sections
			JPopupMenu rowMenu = new JPopupMenu();
			JMenuItem upToHere = new JMenuItem("Complete every previous step");
			upToHere.addActionListener(e ->
			{
				int choice = JOptionPane.showConfirmDialog(HcimGuidePanel.this,
					"Mark every step BEFORE this one complete, across all chapters\n"
						+ "and banks? (This step itself stays unchecked. Steps can\n"
						+ "always be unticked individually afterwards.)",
					"Complete previous steps", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
				if (choice == JOptionPane.OK_OPTION)
				{
					plugin.completeAllStepsBefore(step.getKey());
					rebuildBanks();
				}
			});
			rowMenu.add(upToHere);
			// ...and the mirror: rewind everything past this point (e.g. after
			// an over-eager bulk complete or to redo a stretch of the guide)
			JMenuItem clearAfter = new JMenuItem("Clear every step after this");
			clearAfter.addActionListener(e ->
			{
				int choice = JOptionPane.showConfirmDialog(HcimGuidePanel.this,
					"Un-check every step AFTER this one, across all chapters\n"
						+ "and banks? (This step itself keeps its state. Cleared\n"
						+ "steps won't auto-complete again this session unless\n"
						+ "you re-tick them.)",
					"Clear later steps", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
				if (choice == JOptionPane.OK_OPTION)
				{
					plugin.clearAllStepsAfter(step.getKey());
					rebuildBanks();
				}
			});
			rowMenu.add(clearAfter);
			// optional detours: exclude a step from progress without lying
			// about having done it. Label reflects current state on open.
			JMenuItem skipItem = new JMenuItem("Skip step (exclude from progress)");
			skipItem.addActionListener(e ->
			{
				boolean nowSkipped = !plugin.isSkipped(step.getKey());
				plugin.setSkipped(step.getKey(), nowSkipped);
				lastRenderedState = -1; // force re-render
				syncFromModel();
				updateProgress();
				if (nowSkipped && config.autoCollapseCompleted() && section.isComplete())
				{
					section.setExpanded(false);
					expandNextAfterAutoCollapse(section);
				}
			});
			rowMenu.add(skipItem);
			// steps referencing a video guide (whitelisted hosts only) get a
			// browser-open action; a deliberate click, never automatic
			if (step.getVideoUrl() != null)
			{
				JMenuItem watchVideo = new JMenuItem("Watch video in browser");
				watchVideo.setToolTipText(step.getVideoUrl());
				watchVideo.addActionListener(e -> plugin.openVideoUrl(step.getVideoUrl()));
				rowMenu.add(watchVideo);
			}
			rowMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener()
			{
				@Override
				public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e)
				{
					skipItem.setText(plugin.isSkipped(step.getKey())
						? "Un-skip step" : "Skip step (exclude from progress)");
				}

				@Override
				public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e)
				{
				}

				@Override
				public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e)
				{
				}
			});
			checkBox.setComponentPopupMenu(rowMenu);
			textLabel.setComponentPopupMenu(rowMenu);

			StepCondition cond = plugin.getCondition(step.getKey());
			String target = plugin.getStepTarget(step.getKey());

			// east column: pin button and/or auto-complete badge
			eastColumn.setLayout(new BoxLayout(eastColumn, BoxLayout.Y_AXIS));
			eastColumn.setBackground(getBackground());
			if (target != null)
			{
				pinButton = new JButton("⌖"); // ⌖
				pinButton.setToolTipText("Track \"" + target + "\" - colored arrow + highlight when nearby");
				pinButton.setMargin(new Insets(0, 4, 0, 4));
				pinButton.setFocusPainted(false);
				pinButton.addActionListener(e ->
				{
					boolean alreadyPinned = step.getKey().equals(plugin.getPinnedStepKey());
					plugin.pinStep(alreadyPinned ? null : step);
					setTargetStatus(alreadyPinned ? null : target, false);
					refreshAllPinButtons();
				});
				eastColumn.add(pinButton);
			}
			else
			{
				pinButton = null;
			}
			if (step.getVideoUrl() != null)
			{
				JButton videoButton = new JButton("▶");
				videoButton.setToolTipText("Watch video guide: " + step.getVideoUrl());
				videoButton.setMargin(new Insets(0, 4, 0, 4));
				videoButton.setFocusPainted(false);
				videoButton.addActionListener(e -> plugin.openVideoUrl(step.getVideoUrl()));
				eastColumn.add(videoButton);
			}
			if (cond != null)
			{
				JLabel badge = new JLabel("⚡"); // auto-detectable step
				badge.setToolTipText(cond.describe());
				badge.setForeground(new Color(255, 200, 60));
				badge.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 4));
				eastColumn.add(badge);
			}
			if (eastColumn.getComponentCount() > 0)
			{
				add(eastColumn, BorderLayout.EAST);
			}
			// listen on the TEXT LABEL, not the row: the row's resize event
			// fires before BorderLayout has laid out its children, so the
			// label width read there is one validation cycle stale (0 on the
			// first pass) - the label's own resize fires after its reshape,
			// when getWidth() is current. A changed width changes the wrap
			// point, so the label's preferred HEIGHT must be re-asked.
			textLabel.addComponentListener(new ComponentAdapter()
			{
				@Override
				public void componentResized(ComponentEvent event)
				{
					int width = textLabel.getWidth();
					if (width > 0 && width != lastWrapWidth)
					{
						lastWrapWidth = width;
						textLabel.revalidate();
					}
				}
			});

			// item icon grid for steps with item lists (withdraw/collect
			// conditions AND JSON guides' display-only "(Items: ...)" lists) -
			// wrapped in a left-aligned flow so BorderLayout can't stretch the
			// grid to the full row width (which would inflate the slots)
			java.util.List<ItemReq> gridItems = plugin.getStepItems(step.getKey());
			if (gridItems != null && !gridItems.isEmpty() && config.showItemGrids())
			{
				grid = new ItemGridPanel(gridItems, config.itemPresenceBorders());
				JPanel gridWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
				gridWrap.setOpaque(false);
				gridWrap.add(grid);
				add(gridWrap, BorderLayout.SOUTH);
				grid.updatePresence(plugin.getInventorySnapshot());
				plugin.resolveItemIcons(grid);
			}
			else
			{
				grid = null;
			}
			syncPinVisual();
			syncFromModel(); // skip state: disabled checkbox + struck text
		}

		void syncFromModel()
		{
			boolean done = plugin.isCompleted(step.getKey());
			boolean skipped = plugin.isSkipped(step.getKey());
			if (checkBox.isSelected() != done)
			{
				checkBox.setSelected(done);
			}
			// a skipped step's checkbox is disabled: un-skip first, then tick
			checkBox.setEnabled(!skipped);
			// setting HTML text forces Swing to rebuild the label's document
			// tree (~0.5ms x hundreds of rows), so skip when nothing changed
			int state = skipped ? 2 : (done ? 1 : 0);
			if (lastRenderedState != state)
			{
				refreshText();
			}
		}

		private void refreshText()
		{
			String escaped = escapeHtml(step.getText());
			boolean done = checkBox.isSelected();
			boolean skipped = plugin.isSkipped(step.getKey());
			lastRenderedState = skipped ? 2 : (done ? 1 : 0);
			// no width styling: the label itself wraps its HTML view to the
			// width BorderLayout allocated it (WrappingTextLabel), so the wrap
			// point tracks the real layout instead of a reconstructed number
			java.awt.Font panelFont = panelFont();
			textLabel.setFont(panelFont);
			String family = panelFont.getFamily().replace("'", "");
			String style = "font-size:" + panelFont.getSize()
				+ "px;font-family:'" + family + "'";
			String body;
			if (skipped)
			{
				body = "<strike><span style='color:#6a6a72'><i>" + escaped + " (skipped)</i></span></strike>";
			}
			else if (done && config.dimCompletedSteps())
			{
				body = "<strike><span style='color:#8a8a8a'>" + escaped + "</span></strike>";
			}
			else
			{
				Color semanticColor = semanticStepColor();
				body = semanticColor == null
					? escaped
					: "<span style='color:" + htmlColor(semanticColor) + "'>" + escaped + "</span>";
			}
			textLabel.setText("<html><body style='" + style + "'>" + body + "</body></html>");
			// a rebuilt HTML view starts unsized - re-ask for the preferred
			// height at the label's current width
			textLabel.revalidate();
		}

		private Color semanticStepColor()
		{
			switch (StepTextSemantic.classify(step.getText()))
			{
				case DANGER:
					return config.colorDangerSteps() ? config.dangerStepColor() : null;
				case PREPARATION:
					return config.colorPreparationSteps() ? config.preparationStepColor() : null;
				case TRANSPORT:
					return config.colorTransportSteps() ? config.transportStepColor() : null;
				default:
					return null;
			}
		}

		void syncPinVisual()
		{
			if (pinButton != null)
			{
				boolean pinned = step.getKey().equals(plugin.getPinnedStepKey());
				pinButton.setForeground(pinned ? ColorScheme.BRAND_ORANGE : null);
			}
		}
	}

	private void refreshAllPinButtons()
	{
		for (BankSection section : bankSections)
		{
			for (StepRow row : section.rows)
			{
				row.syncPinVisual();
			}
		}
	}

	private static String htmlColor(Color color)
	{
		Color safe = color == null ? new Color(80, 220, 255) : color;
		return String.format("#%02x%02x%02x", safe.getRed(), safe.getGreen(), safe.getBlue());
	}

	private static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static final java.util.regex.Pattern TRAILING_COLON_WS =
		java.util.regex.Pattern.compile("[:\\s]+$");

	private static String shortTitle(String s)
	{
		String t = TRAILING_COLON_WS.matcher(s).replaceAll("");
		return t.length() <= 20 ? t : t.substring(0, 19) + "…";
	}
}
