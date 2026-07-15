package com.hcimguide;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
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
	private final JComboBox<GuideRegistry.Entry> guideBox = new JComboBox<>();
	private final JComboBox<GuideEpisode> episodeBox = new JComboBox<>();
	private final JPanel banksContainer = new JPanel(new GridBagLayout());

	private Guide guide;
	private final List<BankSection> bankSections = new ArrayList<>();
	private boolean rebuilding;

	HcimGuidePanel(HcimGuidePlugin plugin, HcimGuideConfig config)
	{
		super(false);
		this.plugin = plugin;
		this.config = config;

		setLayout(new BorderLayout(0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		// ---------------- header ----------------
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Guide Overlay");
		title.setFont(FontManager.getRunescapeBoldFont());
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

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(statusLabel);

		targetLabel.setFont(FontManager.getRunescapeSmallFont());
		targetLabel.setForeground(ColorScheme.BRAND_ORANGE);
		header.add(targetLabel);

		overallProgress.setStringPainted(true);
		overallProgress.setForeground(new Color(0, 146, 84));
		overallProgress.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		overallProgress.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		header.add(overallProgress);

		header.add(Box.createVerticalStrut(4));

		searchField.setToolTipText("Search steps in the selected episode");
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				applyFilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				applyFilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				applyFilter();
			}
		});
		header.add(searchField);

		header.add(Box.createVerticalStrut(4));

		episodeBox.setRenderer(new EpisodeRenderer());
		episodeBox.addActionListener(e ->
		{
			if (!rebuilding)
			{
				rebuildBanks();
			}
		});
		header.add(episodeBox);

		header.add(Box.createVerticalStrut(4));

		JButton resume = new JButton("Jump to next unchecked step");
		resume.setFocusPainted(false);
		resume.addActionListener(e -> jumpToNextUnchecked());
		resume.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(resume);

		header.add(Box.createVerticalStrut(6));

		add(header, BorderLayout.NORTH);

		// ---------------- bank list ----------------
		banksContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(banksContainer, BorderLayout.NORTH);

		JScrollPane scrollPane = new JScrollPane(wrapper);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(scrollPane, BorderLayout.CENTER);

		setStatus("Loading guide...");
	}

	/**
	 * The guide is imported once and stored locally; these menu actions are the
	 * only way any content ever changes. Both import actions require explicit
	 * confirmation, and the previous snapshot can always be restored.
	 */
	private JPopupMenu buildImportMenu()
	{
		JPopupMenu menu = new JPopupMenu();

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
		menu.add(exportLocations);

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
			String json;
			try
			{
				json = (String) java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
					.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
			}
			catch (Exception ex)
			{
				setStatus("Clipboard does not contain text");
				return;
			}
			plugin.importLocations(json);
		});
		menu.add(importLocations);

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
			// clipboard only touched after the user confirms
			String code;
			try
			{
				code = (String) java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
					.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
			}
			catch (Exception ex)
			{
				setStatus("Clipboard does not contain text");
				return;
			}
			plugin.importProgress(code);
		});
		menu.add(importProgress);

		return menu;
	}

	// ---------------------------------------------------------------- API used by plugin

	void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	/** Populate the guide dropdown (EDT only). */
	void setGuides(java.util.List<GuideRegistry.Entry> entries, String selectedId)
	{
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
		guideBox.repaint();
	}

	/** Empty the panel when the selected guide has no snapshot yet (EDT only). */
	void clearGuide(String status)
	{
		guide = null;
		rebuilding = true;
		episodeBox.removeAllItems();
		rebuilding = false;
		banksContainer.removeAll();
		bankSections.clear();
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
			if (value instanceof GuideRegistry.Entry)
			{
				GuideRegistry.Entry e = (GuideRegistry.Entry) value;
				String suffix = plugin.isGuideImported(e.getId()) ? "" : "  (not imported)";
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
			}
		}
		updateProgress();
		refreshAllPinButtons();
	}

	/** Called after the plugin auto-advances the pinned step. */
	void onPinChanged(String newTargetName)
	{
		refreshAllPinButtons();
		setTargetStatus(newTargetName, false);
	}

	/** Re-create rows so config toggles (item grids, dimming) apply immediately. */
	void onConfigChanged()
	{
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
		GuideEpisode selected = (GuideEpisode) episodeBox.getSelectedItem();
		int selectedNumber = selected != null ? selected.getNumber() : -1;
		episodeBox.removeAllItems();
		GuideEpisode toSelect = null;
		for (GuideEpisode ep : guide.getEpisodes())
		{
			episodeBox.addItem(ep);
			if (ep.getNumber() == selectedNumber)
			{
				toSelect = ep;
			}
		}
		if (toSelect != null)
		{
			episodeBox.setSelectedItem(toSelect);
		}
		else if (episodeBox.getItemCount() > 0)
		{
			episodeBox.setSelectedIndex(0);
		}
		rebuilding = false;

		rebuildBanks();
	}

	// ---------------------------------------------------------------- internals

	private GuideEpisode currentEpisode()
	{
		return (GuideEpisode) episodeBox.getSelectedItem();
	}

	private void rebuildBanks()
	{
		// detach async icon callbacks of the old generation so discarded
		// component trees can't be resurrected by late image loads
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
		banksContainer.removeAll();
		bankSections.clear();

		GuideEpisode ep = currentEpisode();
		if (ep != null)
		{
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.weightx = 1;
			gbc.fill = GridBagConstraints.HORIZONTAL;
			gbc.insets = new Insets(0, 0, 6, 0);

			String activeId = plugin.getActiveBankId();
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

		applyFilter();
		updateProgress();
		banksContainer.revalidate();
		banksContainer.repaint();
	}

	private void applyFilter()
	{
		String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
		for (BankSection section : bankSections)
		{
			section.filter(q);
		}
		banksContainer.revalidate();
		banksContainer.repaint();
	}

	void updateProgress()
	{
		if (guide == null)
		{
			return;
		}
		int total = guide.totalSteps();
		int done = 0;
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank b : ep.getBanks())
			{
				done += plugin.countCompleted(b.getSteps());
			}
		}
		overallProgress.setMaximum(Math.max(1, total));
		overallProgress.setValue(done);
		overallProgress.setString(done + " / " + total + " steps");

		for (BankSection section : bankSections)
		{
			section.updateHeader();
		}
		episodeBox.repaint();
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
					if (!plugin.isCompleted(step.getKey()))
					{
						searchField.setText("");
						episodeBox.setSelectedItem(ep);
						SwingUtilities.invokeLater(() ->
						{
							for (BankSection section : bankSections)
							{
								boolean isTarget = section.bank.getId().equals(bank.getId());
								section.setExpanded(isTarget);
								if (isTarget)
								{
									// scroll after the collapse/expand relayout settles
									SwingUtilities.invokeLater(() -> section.scrollRectToVisible(
										new Rectangle(0, 0, section.getWidth(), section.getHeight())));
								}
							}
						});
						return;
					}
				}
			}
		}
		setStatus("Everything is checked off. Gz!");
	}

	private class EpisodeRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
		{
			Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (value instanceof GuideEpisode)
			{
				GuideEpisode ep = (GuideEpisode) value;
				int total = ep.totalSteps();
				int done = 0;
				for (GuideBank b : ep.getBanks())
				{
					done += plugin.countCompleted(b.getSteps());
				}
				// generic (non-Episode) guides keep their own chapter titles
				String label = ep.getTitle().regionMatches(true, 0, "Episode", 0, 7)
					? "Episode " + ep.getNumber()
					: shortTitle(ep.getTitle());
				setText(label + "  (" + done + "/" + total + ")");
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
		/** Rows and item grids are created on FIRST expand, so an episode with
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
			headerTitle.setFont(FontManager.getRunescapeBoldFont());
			headerTitle.setForeground(Color.WHITE);
			headerPanel.add(headerTitle, BorderLayout.CENTER);

			headerCount.setFont(FontManager.getRunescapeSmallFont());
			headerCount.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			headerPanel.add(headerCount, BorderLayout.EAST);

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
			headerPanel.setComponentPopupMenu(menu);

			headerPanel.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e))
					{
						setExpanded(!expanded);
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
		}

		boolean isComplete()
		{
			return plugin.countCompleted(bank.getSteps()) == bank.getSteps().size();
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
			int done = plugin.countCompleted(bank.getSteps());
			int total = bank.getSteps().size();
			headerCount.setText(done + "/" + total);
			headerTitle.setForeground(done == total && total > 0 ? new Color(0, 200, 120) : Color.WHITE);

			boolean active = bank.getId().equals(plugin.getActiveBankId());
			headerPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, active ? 3 : 0, 0, 0, ColorScheme.BRAND_ORANGE),
				BorderFactory.createEmptyBorder(5, active ? 3 : 6, 5, 6)));
		}

		void filter(String query)
		{
			if (query.isEmpty())
			{
				setVisible(true);
				for (StepRow row : rows)
				{
					row.setVisible(true);
				}
				return;
			}
			// match against the MODEL so unbuilt (collapsed) sections can be
			// searched without constructing their rows
			boolean any = false;
			for (GuideStep step : bank.getSteps())
			{
				if (step.getText().toLowerCase(Locale.ROOT).contains(query))
				{
					any = true;
					break;
				}
			}
			setVisible(any);
			if (!any)
			{
				return;
			}
			setExpanded(true); // builds rows on demand, only for matching sections
			for (StepRow row : rows)
			{
				row.setVisible(row.step.getText().toLowerCase(Locale.ROOT).contains(query));
			}
		}

		private void bulk(boolean completed)
		{
			plugin.setCompletedBulk(bank.getSteps(), completed);
			for (StepRow row : rows)
			{
				row.syncFromModel();
			}
			updateProgress();
			if (completed && config.autoCollapseCompleted())
			{
				setExpanded(false);
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
					steps.addAll(b.getSteps());
				}
			}
			if (!found)
			{
				// guide was replaced while the menu was open - never complete
				// the whole guide off a stale anchor
				setStatus("Guide changed - nothing was marked");
				return;
			}
			plugin.setCompletedBulk(steps, true);
			rebuildBanks();
		}
	}

	// ---------------------------------------------------------------- step row

	private class StepRow extends JPanel
	{
		private final GuideStep step;
		private final JCheckBox checkBox = new JCheckBox();
		private final BankSection section;
		private final JButton pinButton;
		private final ItemGridPanel grid;
		/** Last completion state rendered into the HTML label; null = never rendered. */
		private Boolean lastRenderedDone;

		StepRow(BankSection section, GuideStep step)
		{
			this.section = section;
			this.step = step;

			setLayout(new BorderLayout());
			setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
			setBorder(BorderFactory.createEmptyBorder(2, 6 + step.getDepth() * 12, 2, 2));

			checkBox.setSelected(plugin.isCompleted(step.getKey()));
			checkBox.setBackground(getBackground());
			checkBox.setFocusPainted(false);
			checkBox.setVerticalTextPosition(JCheckBox.TOP);
			refreshText();
			checkBox.addActionListener(e ->
			{
				plugin.setCompleted(step.getKey(), checkBox.isSelected());
				lastRenderedDone = null; // force re-render
				refreshText();
				updateProgress(); // refreshes every bank header including ours
				if (checkBox.isSelected() && config.autoCollapseCompleted() && section.isComplete())
				{
					section.setExpanded(false);
				}
			});
			add(checkBox, BorderLayout.CENTER);

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
			checkBox.setComponentPopupMenu(rowMenu);

			StepCondition cond = plugin.getCondition(step.getKey());
			String target = plugin.getStepTarget(step.getKey());

			// east column: pin button and/or auto-complete badge
			JPanel east = new JPanel();
			east.setLayout(new BoxLayout(east, BoxLayout.Y_AXIS));
			east.setBackground(getBackground());
			if (target != null)
			{
				pinButton = new JButton("⌖"); // ⌖
				pinButton.setToolTipText("Track \"" + target + "\" - hint arrow + highlight when nearby");
				pinButton.setMargin(new Insets(0, 4, 0, 4));
				pinButton.setFocusPainted(false);
				pinButton.addActionListener(e ->
				{
					boolean alreadyPinned = step.getKey().equals(plugin.getPinnedStepKey());
					plugin.pinStep(alreadyPinned ? null : step);
					setTargetStatus(alreadyPinned ? null : target, false);
					refreshAllPinButtons();
				});
				east.add(pinButton);
			}
			else
			{
				pinButton = null;
			}
			if (cond != null)
			{
				JLabel badge = new JLabel("⚡"); // auto-detectable step
				badge.setToolTipText(cond.describe());
				badge.setForeground(new Color(255, 200, 60));
				badge.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 4));
				east.add(badge);
			}
			if (east.getComponentCount() > 0)
			{
				add(east, BorderLayout.EAST);
			}

			// item icon grid for withdraw/collect steps
			if (cond != null && cond.getType() == StepCondition.Type.ITEMS_IN_INVENTORY
				&& config.showItemGrids())
			{
				grid = new ItemGridPanel(cond.getItems());
				add(grid, BorderLayout.SOUTH);
				grid.updatePresence(plugin.getInventorySnapshot());
				plugin.resolveItemIcons(grid);
			}
			else
			{
				grid = null;
			}
			syncPinVisual();
		}

		void syncFromModel()
		{
			boolean done = plugin.isCompleted(step.getKey());
			if (checkBox.isSelected() != done)
			{
				checkBox.setSelected(done);
			}
			// setting HTML text forces Swing to rebuild the label's document
			// tree (~0.5ms x hundreds of rows), so skip when nothing changed
			if (lastRenderedDone == null || lastRenderedDone != done)
			{
				refreshText();
			}
		}

		private void refreshText()
		{
			String escaped = escapeHtml(step.getText());
			boolean done = checkBox.isSelected();
			lastRenderedDone = done;
			String style = "width:140px;font-size:" + config.panelTextSize().getPx() + "px";
			String body;
			if (done && config.dimCompletedSteps())
			{
				body = "<strike><span style='color:#8a8a8a'>" + escaped + "</span></strike>";
			}
			else
			{
				body = escaped;
			}
			checkBox.setText("<html><body style='" + style + "'>" + body + "</body></html>");
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

	private static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String shortTitle(String s)
	{
		String t = s.replaceAll("[:\\s]+$", "");
		return t.length() <= 20 ? t : t.substring(0, 19) + "…";
	}
}
