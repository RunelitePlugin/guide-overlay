package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.QuestState;
import net.runelite.api.TileItem;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Guide Overlay",
	description = "Wiki guides as an in-client checklist with auto-completion and target highlighting; B0aty HCIM Guide V3 built in",
	tags = {"hcim", "ironman", "guide", "checklist", "b0aty"}
)
// required for BankTagsService injection: its binding lives in the Bank Tags
// plugin's injector, which is only visible to declared dependents
@PluginDependency(BankTagsPlugin.class)
public class HcimGuidePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(HcimGuidePlugin.class);
	private static final Type STRING_SET = new TypeToken<Set<String>>()
	{
	}.getType();

	/** Evaluate auto-completion conditions every N game ticks. */
	private static final int EVAL_INTERVAL_TICKS = 4;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private HcimGuideConfig config;

	@Inject
	private GuideService guideService;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TargetOverlay targetOverlay;

	@Inject
	private HudOverlay hudOverlay;

	@Inject
	private DirectionArrowOverlay directionArrowOverlay;

	@Inject
	private GuideRegistry guideRegistry;

	@Inject
	private NpcLocationStore locationStore;

	@Inject
	private LocationDbDownloader locationDbDownloader;

	@Inject
	private WorldMapPointManager worldMapPointManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ItemIconResolver iconResolver;

	@Inject
	private BankTagIntegration bankTagIntegration;

	@Inject
	private BankStockTracker stockTracker;

	@Inject
	private PathfinderIntegration pathfinder;

	/** Bank interface group id - the symbolic gameval constant, not a magic 12. */
	private static final int BANK_GROUP_ID = InterfaceID.BANKMAIN;

	private NavigationButton navButton;
	private HcimGuidePanel panel;

	private final Set<String> completedSteps = new HashSet<>();

	/**
	 * False after shutDown: executor tasks and fetch callbacks queued before a
	 * disable check this so they can't repopulate state (or write config /
	 * touch the removed panel) after the plugin was turned off.
	 */
	private volatile boolean active;
	private volatile Guide currentGuide;
	private volatile String currentGuideId = GuideRegistry.BUILTIN_ID;
	private volatile Map<String, StepCondition> conditions = Collections.emptyMap();
	/** Step key -&gt; extracted NPC target name; precomputed at import (never re-extracted per tick). */
	private volatile Map<String, String> stepTargets = Collections.emptyMap();
	/** Normalized target names of the current guide - bounds what the location store learns. */
	private volatile Set<String> targetNamesNorm = Collections.emptySet();
	private volatile InventorySnapshot inventory = InventorySnapshot.EMPTY;

	// far-target pointing (client thread only)
	private WorldPoint farTarget;
	private WorldMapPoint mapMarker;
	private WorldPoint markerPoint;
	private WorldPoint lastArrowPoint;

	// cache for findActiveBank: invalidated whenever completion state or the guide changes
	private volatile GuideBank activeBankCache;
	private volatile Guide activeBankCacheGuide;
	private volatile boolean activeBankDirty = true;

	/**
	 * Steps that were auto-completed once, or manually unticked - never
	 * auto-completed again this session, so manual control always wins.
	 */
	private final Set<String> autoSuppressed = Collections.synchronizedSet(new HashSet<>());

	private int tickCounter;

	// pinned step target tracking
	private volatile String pinnedStepKey;
	private volatile String targetName;
	private volatile NPC targetNpc;
	private boolean hintArrowSet;

	// active-bank highlighting (all fields client-thread only; overlay renders on client thread)
	private final Map<TileItem, GroundHighlight> groundItems = new HashMap<>();
	private List<NPC> stepNpcs = new ArrayList<>();
	private List<GroundHighlight> groundHighlights = new ArrayList<>();

	// routing (computed on the client thread every EVAL_INTERVAL_TICKS with
	// movement/target/stock guards, so most cycles are a few comparisons)
	private volatile String routeSuggestion;    // HUD line; null = nothing to suggest
	private WorldPoint nextStepPoint;           // next unchecked step's known location
	private WorldPoint lastRoutePlayer;
	private WorldPoint lastRouteObjective;
	private long lastStockRevision = -1;
	private volatile boolean routeDirty = true; // config/stock changed -> recompute

	// icon prefetch (small buffer of upcoming banks; executor-side warm-up)
	private volatile String lastPrefetchKey;

	@Provides
	HcimGuideConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HcimGuideConfig.class);
	}

	@Override
	protected void startUp()
	{
		active = true;
		panel = new HcimGuidePanel(this, config);

		BufferedImage icon = ImageUtil.loadImageResource(HcimGuidePlugin.class, "panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Guide Overlay")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(targetOverlay);
		overlayManager.add(hudOverlay);
		overlayManager.add(directionArrowOverlay);

		// scrub a bank tag left behind by a crashed session if the feature is off
		// (when it's on, the first sync cycle rebuilds the tag anyway)
		if (!config.bankTagIntegration())
		{
			bankTagIntegration.cleanup();
		}

		// load the locally stored snapshot only - the plugin NEVER fetches on its own
		executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			guideService.migrateLegacyStore();
			locationStore.load();
			stockTracker.loadBankColumn(); // persisted "in bank" teleport items
			String selected = configManager.getConfiguration(HcimGuideConfig.GROUP, "selectedGuide");
			if (selected == null || guideRegistry.byId(selected) == null)
			{
				selected = GuideRegistry.BUILTIN_ID;
			}
			final String selectedFinal = selected;
			SwingUtilities.invokeLater(() -> panel.setGuides(guideRegistry.list(), selectedFinal));
			// the one-click import dialog pops at startup only ONCE ever (the
			// true first run); afterwards an unimported guide just shows its
			// panel status - no modal over the game at every client boot.
			// Selecting a guide from the dropdown still offers the dialog.
			boolean firstRun = !"true".equals(configManager.getConfiguration(HcimGuideConfig.GROUP, "importPrompted"));
			if (firstRun)
			{
				configManager.setConfiguration(HcimGuideConfig.GROUP, "importPrompted", "true");
			}
			selectGuideInternal(selected, firstRun);
		});
	}

	@Override
	protected void shutDown()
	{
		active = false;
		overlayManager.remove(targetOverlay);
		overlayManager.remove(hudOverlay);
		overlayManager.remove(directionArrowOverlay);
		clientToolbar.removeNavigation(navButton);
		executor.execute(locationStore::saveIfDirty);
		bankTagIntegration.cleanup();

		// the plugin instance is reused on re-enable: clear all transient state,
		// including the guide model so a deleted snapshot can't leave stale
		// highlighting/auto-completion running after re-enable
		pinnedStepKey = null;
		targetName = null;
		autoSuppressed.clear();
		inventory = InventorySnapshot.EMPTY;
		currentGuide = null;
		conditions = Collections.emptyMap();
		stepTargets = Collections.emptyMap();
		targetNamesNorm = Collections.emptySet();
		activeBankCache = null;
		activeBankCacheGuide = null;
		activeBankDirty = true;
		routeSuggestion = null;
		routeDirty = true;
		lastPrefetchKey = null;
		stockTracker.reset();
		clientThread.invokeLater(() ->
		{
			if (hintArrowSet)
			{
				client.clearHintArrow();
				hintArrowSet = false;
			}
			targetNpc = null;
			lastArrowPoint = null;
			clearFarTarget();
			groundItems.clear();
			stepNpcs = new ArrayList<>();
			groundHighlights = new ArrayList<>();
			nextStepPoint = null;
			lastRoutePlayer = null;
			lastRouteObjective = null;
			pathfinder.clear(); // take the drawn path down with the plugin
		});
	}

	// ------------------------------------------------------------------ guide selection & import

	String getCurrentGuideId()
	{
		return currentGuideId;
	}

	boolean isGuideImported(String guideId)
	{
		return guideService.hasSnapshot(guideId);
	}

	/** Switch the panel to another guide (EDT entry point). */
	void selectGuide(String guideId)
	{
		executor.execute(() -> selectGuideInternal(guideId, true));
	}

	/**
	 * Loads a guide's stored snapshot and its progress. When the guide has
	 * never been imported, the panel offers a one-click import instead -
	 * on first run this doubles as the "pre-import" prompt, so a new user is
	 * one confirmation away from a working checklist (never a silent fetch).
	 */
	private void selectGuideInternal(String guideId, boolean offerImportIfMissing)
	{
		if (!active)
		{
			return;
		}
		GuideRegistry.Entry entry = guideRegistry.byId(guideId);
		if (entry == null)
		{
			return;
		}

		// SCOPING: clear the previous guide's model BEFORE anything else - and
		// before the new snapshot's (potentially slow, multi-MB) disk read and
		// parse below. While these fields are cleared every game-tick consumer
		// (highlights, bank tags, markers, auto-completion) sees currentGuide ==
		// null and no-ops, so the OLD guide's steps can never be evaluated
		// against the NEW guide's progress store. applyGuide() repopulates
		// everything atomically at the end.
		currentGuide = null;
		conditions = Collections.emptyMap();
		stepTargets = Collections.emptyMap();
		targetNamesNorm = Collections.emptySet();
		activeBankCache = null;
		activeBankCacheGuide = null;
		activeBankDirty = true;

		currentGuideId = guideId;
		configManager.setConfiguration(HcimGuideConfig.GROUP, "selectedGuide", guideId);
		loadCompletedSteps();
		autoSuppressed.clear();
		pinStep(null);

		Guide stored = null;
		try
		{
			stored = guideService.loadStored(guideId);
		}
		catch (Exception e)
		{
			log.warn("Failed to load stored guide {}", guideId, e);
		}
		if (stored != null)
		{
			applyGuide(stored, "Guide loaded from local snapshot");
			return;
		}

		final GuideRegistry.Entry finalEntry = entry;
		SwingUtilities.invokeLater(() ->
		{
			panel.clearGuide("\"" + finalEntry.getTitle() + "\" is not imported yet - use the "
				+ "⋮ menu → Import guide");
			// suppressed when the caller is about to start an import itself
			// (add-from-link), so the user never sees a redundant dialog racing
			// an already-running download
			if (offerImportIfMissing && finalEntry.isDownloadable())
			{
				panel.offerImport(finalEntry);
			}
		});
	}

	/**
	 * Explicit, user-initiated one-time download+import for the selected guide.
	 * Wiki guides fetch via the wiki API; built-in direct-URL guides (e.g.
	 * BRUHsailer's JSON) fetch their trusted raw URL. Network happens only
	 * here and only after the user confirms a dialog.
	 */
	void importSelectedFromWiki()
	{
		GuideRegistry.Entry entry = guideRegistry.byId(currentGuideId);
		if (entry == null || !entry.isDownloadable())
		{
			SwingUtilities.invokeLater(() ->
				panel.setStatus("This guide has no download source - import it from a file instead"));
			return;
		}
		final String guideId = entry.getId();
		SwingUtilities.invokeLater(() -> panel.setStatus("Importing \"" + entry.getTitle() + "\" (one time)..."));

		// hop onto the (single-threaded) executor so a guide switch that is
		// queued there can never be clobbered by a stale fetch result
		java.util.function.BiConsumer<Guide, String> onSuccess = (guide, storageWarning) -> executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			if (guideId.equals(currentGuideId))
			{
				applyGuide(guide, storageWarning != null
					? storageWarning
					: "Guide imported and stored locally");
			}
			SwingUtilities.invokeLater(() -> panel.refreshGuideListLabels());
			// first successful guide import: offer the full location DB once,
			// so a fresh install is two clicks from full functionality
			if (!"true".equals(configManager.getConfiguration(HcimGuideConfig.GROUP, "fullDbPrompted")))
			{
				configManager.setConfiguration(HcimGuideConfig.GROUP, "fullDbPrompted", "true");
				SwingUtilities.invokeLater(() -> panel.offerFullDbDownload());
			}
		});
		java.util.function.Consumer<String> onError = error -> SwingUtilities.invokeLater(() -> panel.setStatus(error));

		if (entry.getSourceUrl() != null)
		{
			guideService.fetchUrl(guideId, entry.getSourceUrl(), onSuccess, onError);
		}
		else
		{
			guideService.fetch(guideId, entry.getWikiPage(), onSuccess, onError);
		}
	}

	/**
	 * Adds a guide from a pasted wiki link, then imports it (the add action
	 * itself was the user's explicit consent).
	 */
	void addGuideFromLink(String link)
	{
		executor.execute(() ->
		{
			try
			{
				String pageTitle = WikiUrl.pageTitle(link);
				GuideRegistry.Entry entry = guideRegistry.add(WikiUrl.displayName(pageTitle), pageTitle);
				SwingUtilities.invokeLater(() -> panel.setGuides(guideRegistry.list(), entry.getId()));
				selectGuideInternal(entry.getId(), false);
				if (!guideService.hasSnapshot(entry.getId()))
				{
					importSelectedFromWiki();
				}
			}
			catch (IllegalArgumentException e)
			{
				SwingUtilities.invokeLater(() -> panel.setStatus(e.getMessage()));
			}
		});
	}

	/** Removes a user-added guide from the dropdown (snapshot files are left on disk). */
	void removeSelectedGuide()
	{
		executor.execute(() ->
		{
			if (guideRegistry.remove(currentGuideId))
			{
				SwingUtilities.invokeLater(() -> panel.setGuides(guideRegistry.list(), GuideRegistry.BUILTIN_ID));
				selectGuideInternal(GuideRegistry.BUILTIN_ID, true);
			}
			else
			{
				SwingUtilities.invokeLater(() -> panel.setStatus("The built-in guide can't be removed"));
			}
		});
	}

	/**
	 * Add a brand-new guide entry (no wiki page) and import its content from a
	 * user-chosen file. Runs on the single-threaded executor, so the select
	 * happens before the import.
	 */
	void addGuideFromFile(String title, java.io.File file)
	{
		executor.execute(() ->
		{
			GuideRegistry.Entry entry = guideRegistry.add(title, null);
			SwingUtilities.invokeLater(() -> panel.setGuides(guideRegistry.list(), entry.getId()));
			selectGuideInternal(entry.getId(), false);
			importFromFile(file);
		});
	}

	/**
	 * Catch-up helper available on EVERY step of EVERY guide (works even for
	 * guides without bank sections): completes all steps that come before the
	 * given step in guide order, across banks and chapters, in one bulk
	 * persist. The step itself stays unchecked.
	 */
	void completeAllStepsBefore(String stepKey)
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			return;
		}
		List<GuideStep> before = new ArrayList<>();
		boolean found = false;
		outer:
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					if (step.getKey().equals(stepKey))
					{
						found = true;
						break outer;
					}
					before.add(step);
				}
			}
		}
		// if the anchor step isn't in the CURRENT guide (a re-import or guide
		// switch landed while the confirm dialog was open), do nothing rather
		// than complete the entire guide
		if (!found)
		{
			SwingUtilities.invokeLater(() ->
				panel.setStatus("Guide changed while confirming - nothing was marked"));
			return;
		}
		setCompletedBulk(before, true);
	}

	/** Explicit import from a local wikitext file chosen by the user, into the selected guide. */
	void importFromFile(java.io.File file)
	{
		final String guideId = currentGuideId;
		SwingUtilities.invokeLater(() -> panel.setStatus("Importing guide from file..."));
		executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				if (file.length() > 10L * 1024 * 1024)
				{
					SwingUtilities.invokeLater(() ->
						panel.setStatus("File too large (over 10MB) - guides are plain wikitext"));
					return;
				}
				String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
					java.nio.charset.StandardCharsets.UTF_8);
				Guide guide = guideService.importText(guideId, text);
				if (guideId.equals(currentGuideId))
				{
					applyGuide(guide, "Guide imported from " + file.getName());
				}
				SwingUtilities.invokeLater(() -> panel.refreshGuideListLabels());
			}
			catch (Exception e)
			{
				log.warn("File import failed", e);
				SwingUtilities.invokeLater(() -> panel.setStatus("Import failed: " + e.getMessage()));
			}
		});
	}

	/**
	 * Export current progress to the system clipboard as a compact text code.
	 * Gzip/base64 work runs on the executor so the EDT never blocks; only the
	 * clipboard write and status update return to the EDT.
	 */
	void exportProgress()
	{
		executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				// one consistent snapshot: copy under the lock, encode outside it
				Set<String> snapshot;
				synchronized (completedSteps)
				{
					snapshot = new HashSet<>(completedSteps);
				}
				final String code = ProgressCodec.encode(snapshot, currentGuideId);
				final int count = snapshot.size();
				SwingUtilities.invokeLater(() ->
				{
					try
					{
						java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
							.setContents(new java.awt.datatransfer.StringSelection(code), null);
						panel.setStatus("Progress code (" + count + " steps) copied to clipboard");
					}
					catch (Exception e)
					{
						log.warn("Clipboard write failed", e);
						panel.setStatus("Export failed: " + e.getMessage());
					}
				});
			}
			catch (Exception e)
			{
				log.warn("Progress export failed", e);
				SwingUtilities.invokeLater(() -> panel.setStatus("Export failed: " + e.getMessage()));
			}
		});
	}

	/**
	 * Import progress from a code (from {@link #exportProgress()}) and REPLACE
	 * current progress with it. The panel confirms with the user before calling.
	 */
	void importProgress(String code)
	{
		// decode (gzip/base64, possibly hostile input) off the EDT
		executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				ProgressCodec.Decoded decoded = ProgressCodec.decode(code);
				final String note = decoded.guideId != null && !decoded.guideId.equals(currentGuideId)
					? " (note: exported from a different guide)"
					: "";
				synchronized (completedSteps)
				{
					completedSteps.clear();
					completedSteps.addAll(decoded.keys);
				}
				autoSuppressed.clear();
				activeBankDirty = true;
				persistCompletedSteps();
				SwingUtilities.invokeLater(() ->
				{
					panel.refreshFromModel();
					panel.setStatus("Progress imported: " + decoded.keys.size() + " steps" + note);
				});
			}
			catch (IllegalArgumentException e)
			{
				SwingUtilities.invokeLater(() -> panel.setStatus("Import failed: " + e.getMessage()));
			}
			catch (Exception e)
			{
				log.warn("Progress import failed", e);
				SwingUtilities.invokeLater(() -> panel.setStatus("Import failed: " + e.getMessage()));
			}
		});
	}

	/**
	 * One-time, user-confirmed download of the full-game NPC location
	 * database. Never overwrites observed/imported positions.
	 */
	void downloadFullLocationDb()
	{
		SwingUtilities.invokeLater(() -> panel.setStatus("Downloading full location database (one time)..."));
		locationDbDownloader.download(
			added -> SwingUtilities.invokeLater(() ->
				panel.setStatus("Full database loaded: " + added + " new locations (your observed positions kept)")),
			error -> SwingUtilities.invokeLater(() -> panel.setStatus(error)));
	}

	/** Export the NPC location database to the clipboard as shareable JSON. */
	void exportLocations()
	{
		executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				String json = locationStore.exportJson();
				final String finalJson = json;
				SwingUtilities.invokeLater(() ->
				{
					try
					{
						java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
							.setContents(new java.awt.datatransfer.StringSelection(finalJson), null);
						panel.setStatus("NPC locations copied to clipboard");
					}
					catch (Exception e)
					{
						// AWT clipboard throws IllegalStateException when another
						// app holds it (common on Windows) - fail visibly
						log.warn("Clipboard write failed", e);
						panel.setStatus("Export failed: " + e.getMessage());
					}
				});
			}
			catch (Exception e)
			{
				log.warn("Location export failed", e);
				SwingUtilities.invokeLater(() -> panel.setStatus("Export failed: " + e.getMessage()));
			}
		});
	}

	/** Import NPC locations from shared JSON (community dataset or a friend's export). */
	void importLocations(String json)
	{
		executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				int count = locationStore.importJson(json);
				locationStore.saveIfDirty();
				SwingUtilities.invokeLater(() ->
					panel.setStatus("Imported " + count + " NPC locations"));
			}
			catch (IllegalArgumentException e)
			{
				SwingUtilities.invokeLater(() -> panel.setStatus("Import failed: " + e.getMessage()));
			}
			catch (Exception e)
			{
				log.warn("Location import failed", e);
				SwingUtilities.invokeLater(() -> panel.setStatus("Import failed: " + e.getMessage()));
			}
		});
	}

	/** The bank the player is currently working through (cached; O(1)). Used by the HUD. */
	GuideBank getActiveBank()
	{
		Guide guide = currentGuide;
		return guide == null ? null : findActiveBank(guide);
	}

	/** Swap the selected guide back to its previous snapshot (undo a bad import). */
	void restorePreviousImport()
	{
		final String guideId = currentGuideId;
		executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			Guide guide = guideService.restorePrevious(guideId);
			if (guide != null && guideId.equals(currentGuideId))
			{
				applyGuide(guide, "Previous guide snapshot restored");
			}
			else if (guide == null)
			{
				SwingUtilities.invokeLater(() -> panel.setStatus("No previous snapshot to restore"));
			}
		});
	}

	/**
	 * Store the guide and precompute everything derivable from step text
	 * (auto-completion conditions, NPC target names) so per-tick code and
	 * panel rebuilds only do map lookups, never regex work.
	 */
	private void applyGuide(Guide guide, String status)
	{
		Map<String, StepCondition> cond = new HashMap<>();
		Map<String, String> targets = new HashMap<>();
		Set<String> namesNorm = new HashSet<>();
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					StepCondition c = ConditionParser.parse(step.getText());
					if (c != null)
					{
						cond.put(step.getKey(), c);
					}
					String target = TargetExtractor.extract(step.getText());
					if (target == null)
					{
						// gather steps ("Collect 3x Logs") get the item name as
						// their target, so the compass can point at known
						// permanent item spawns from the location database
						List<ItemReq> gather = ItemListParser.parseGather(step.getText());
						if (gather != null && !gather.isEmpty())
						{
							target = gather.get(0).getName();
						}
					}
					if (target != null)
					{
						targets.put(step.getKey(), target);
						namesNorm.add(Names.normalize(target));
					}
				}
			}
		}
		conditions = cond;
		stepTargets = targets;
		targetNamesNorm = namesNorm;
		currentGuide = guide;
		activeBankDirty = true;
		routeDirty = true;      // new model -> new objective/candidates
		lastPrefetchKey = null; // re-warm icons for the new active bank
		SwingUtilities.invokeLater(() -> panel.setGuide(guide, status));
	}

	StepCondition getCondition(String stepKey)
	{
		return conditions.get(stepKey);
	}

	/** Precomputed NPC target for a step, or null when the step has none. */
	String getStepTarget(String stepKey)
	{
		return stepTargets.get(stepKey);
	}

	InventorySnapshot getInventorySnapshot()
	{
		return inventory;
	}

	/** Resolve item ids for a grid off the EDT, then apply icons. */
	void resolveItemIcons(ItemGridPanel grid)
	{
		executor.execute(() ->
		{
			int[] ids = iconResolver.resolve(grid.getItems());
			grid.applyIcons(itemManager, ids);
		});
	}

	// ------------------------------------------------------------------ progress

	boolean isCompleted(String stepKey)
	{
		synchronized (completedSteps)
		{
			return completedSteps.contains(stepKey);
		}
	}

	void setCompleted(String stepKey, boolean completed)
	{
		if (!completed)
		{
			// manual untick: never fight the user by re-auto-completing it
			autoSuppressed.add(stepKey);
		}
		synchronized (completedSteps)
		{
			if (completed)
			{
				completedSteps.add(stepKey);
			}
			else
			{
				completedSteps.remove(stepKey);
			}
		}
		activeBankDirty = true;
		persistCompletedSteps();
	}

	/** Bulk update used by "mark bank complete" etc. Persists once. */
	void setCompletedBulk(Iterable<GuideStep> steps, boolean completed)
	{
		synchronized (completedSteps)
		{
			for (GuideStep s : steps)
			{
				if (completed)
				{
					completedSteps.add(s.getKey());
				}
				else
				{
					completedSteps.remove(s.getKey());
					autoSuppressed.add(s.getKey());
				}
			}
		}
		activeBankDirty = true;
		persistCompletedSteps();
	}

	int countCompleted(Iterable<GuideStep> steps)
	{
		int n = 0;
		synchronized (completedSteps)
		{
			for (GuideStep s : steps)
			{
				if (completedSteps.contains(s.getKey()))
				{
					n++;
				}
			}
		}
		return n;
	}

	/*
	 * Progress storage routing: each guide has an isolated progress key.
	 * With per-character progress enabled and a character profile active,
	 * progress lives in RSProfile-scoped config (each character has its own
	 * checklist); otherwise the shared global key is used. Changes made while
	 * logged out go to the shared store (the user is told once).
	 */

	/** One-time hint flag for logged-out edits with per-character mode on. */
	private boolean warnedLoggedOutEdit;
	/** Last non-null RSProfile key seen, to detect real character switches. */
	private volatile String lastRsProfileKey;

	/** Progress config key for the current guide (each guide has isolated progress). */
	private String progressKey()
	{
		return HcimGuideConfig.COMPLETED_STEPS_KEY + "." + currentGuideId;
	}

	private String readProgressJson()
	{
		String key = progressKey();
		// pre-multi-guide progress lived under the un-suffixed key
		boolean builtin = GuideRegistry.BUILTIN_ID.equals(currentGuideId);
		if (config.perCharacterProgress())
		{
			String v = configManager.getRSProfileConfiguration(HcimGuideConfig.GROUP, key);
			if (v == null && builtin)
			{
				v = configManager.getRSProfileConfiguration(HcimGuideConfig.GROUP, HcimGuideConfig.COMPLETED_STEPS_KEY);
			}
			if (v != null)
			{
				return v;
			}
		}
		String v = configManager.getConfiguration(HcimGuideConfig.GROUP, key);
		if (v == null && builtin)
		{
			v = configManager.getConfiguration(HcimGuideConfig.GROUP, HcimGuideConfig.COMPLETED_STEPS_KEY);
		}
		return v;
	}

	private void writeProgressJson(String json)
	{
		if (config.perCharacterProgress() && configManager.getRSProfileKey() != null)
		{
			configManager.setRSProfileConfiguration(HcimGuideConfig.GROUP, progressKey(), json);
		}
		else
		{
			configManager.setConfiguration(HcimGuideConfig.GROUP, progressKey(), json);
			if (config.perCharacterProgress() && !warnedLoggedOutEdit)
			{
				// logged-out edits go to the shared store; a character that already
				// has its own progress won't show them after login - say so once
				warnedLoggedOutEdit = true;
				SwingUtilities.invokeLater(() ->
					panel.setStatus("Not logged in - changes saved to shared progress"));
			}
		}
	}

	private void loadCompletedSteps()
	{
		synchronized (completedSteps)
		{
			completedSteps.clear();
			String json = readProgressJson();
			if (json != null && !json.isEmpty())
			{
				try
				{
					Set<String> saved = gson.fromJson(json, STRING_SET);
					if (saved != null)
					{
						completedSteps.addAll(saved);
					}
				}
				catch (Exception e)
				{
					log.warn("Could not parse saved progress", e);
				}
			}
		}
		activeBankDirty = true;
	}

	private void persistCompletedSteps()
	{
		String json;
		synchronized (completedSteps)
		{
			json = gson.toJson(completedSteps);
		}
		writeProgressJson(json);
	}

	/**
	 * On login/character switch: seed a character that has no per-character
	 * progress yet from the shared store (one-time copy, so enabling the
	 * option doesn't appear to wipe existing progress), then reload.
	 */
	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		String key = progressKey();
		if (config.perCharacterProgress() && configManager.getRSProfileKey() != null
			&& configManager.getRSProfileConfiguration(HcimGuideConfig.GROUP, key) == null)
		{
			String shared = configManager.getConfiguration(HcimGuideConfig.GROUP, key);
			if (shared != null && !shared.isEmpty())
			{
				configManager.setRSProfileConfiguration(HcimGuideConfig.GROUP, key, shared);
			}
		}
		loadCompletedSteps();
		// only reset the manual-untick suppression when this is really a
		// DIFFERENT character - relogging the same character must not let
		// previously unticked steps re-auto-complete ("manual always wins")
		String profileKey = configManager.getRSProfileKey();
		if (profileKey != null && !profileKey.equals(lastRsProfileKey))
		{
			lastRsProfileKey = profileKey;
			autoSuppressed.clear();
		}
		SwingUtilities.invokeLater(() -> panel.refreshFromModel());
	}

	// ------------------------------------------------------------------ inventory tracking

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		ItemContainer container = event.getItemContainer();
		if (container == null)
		{
			return;
		}

		// teleport stock tracking: equipment and bank columns (client thread)
		if (event.getContainerId() == InventoryID.WORN)
		{
			stockTracker.update(BankStockTracker.SRC_EQUIPMENT, container.getItems());
			return;
		}
		if (event.getContainerId() == InventoryID.BANK)
		{
			stockTracker.update(BankStockTracker.SRC_BANK, container.getItems());
			// persist off the client thread so "in bank" survives restarts;
			// active-guarded so a queued save can't fire after shutDown and
			// persist the reset (empty) tracker over real data
			executor.execute(() ->
			{
				if (active)
				{
					stockTracker.saveBankColumn();
				}
			});
			return;
		}
		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}

		// keyed by normalized/singularized name here (client thread, ~28 items)
		// so all downstream presence checks are plain map lookups
		Map<String, Integer> byNorm = new HashMap<>();
		Map<String, Integer> bySing = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			int canonicalId = itemManager.canonicalize(item.getId());
			ItemComposition comp = itemManager.getItemComposition(canonicalId);
			String name = comp.getName();
			if (name != null && !"null".equals(name))
			{
				byNorm.merge(Names.normalize(name), item.getQuantity(), Integer::sum);
				bySing.merge(Names.singularize(name), item.getQuantity(), Integer::sum);
			}
		}
		stockTracker.update(BankStockTracker.SRC_INVENTORY, container.getItems());
		final InventorySnapshot snapshot = new InventorySnapshot(byNorm, bySing);
		inventory = snapshot;
		SwingUtilities.invokeLater(() -> panel.updateInventory(snapshot));
	}

	// ------------------------------------------------------------------ ground item tracking

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		// deliberately collected even while highlighting is toggled off: despawn
		// events can't be replayed, so skipping here would leave permanent gaps
		// if the user enables the option mid-scene. Cost is one cached
		// composition lookup per spawn.
		TileItem item = event.getItem();
		ItemComposition comp = itemManager.getItemComposition(item.getId());
		String name = comp.getName();
		if (name != null && !"null".equals(name))
		{
			groundItems.put(item, new GroundHighlight(event.getTile(), name));
		}
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		groundItems.remove(event.getItem());
	}

	/**
	 * Location learning: record the exact position of any NPC the current
	 * guide references, so far-target arrows use observed data rather than
	 * guesses. Bounded by the guide's target-name set.
	 */
	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if (npc.getName() == null || targetNamesNorm.isEmpty())
		{
			return;
		}
		String norm = Names.normalize(Text.removeTags(npc.getName()));
		if (targetNamesNorm.contains(norm))
		{
			locationStore.learn(norm, npc.getWorldLocation());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		stepNpcs.remove(event.getNpc());
		if (targetNpc == event.getNpc())
		{
			// clear the arrow NOW: updateTargetTracking's change-detection would
			// otherwise see null == null next tick and leave a stale arrow bound
			// to a recycled NPC index
			targetNpc = null;
			clearArrowOnClientThread();
			final String name = targetName;
			if (name != null)
			{
				SwingUtilities.invokeLater(() -> panel.setTargetStatus(name, false));
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING || event.getGameState() == GameState.LOGIN_SCREEN)
		{
			groundItems.clear();
			groundHighlights = new ArrayList<>();
			stepNpcs = new ArrayList<>();
			targetNpc = null;
			clearArrowOnClientThread();
			final String name = targetName;
			if (name != null)
			{
				SwingUtilities.invokeLater(() -> panel.setTargetStatus(name, false));
			}
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			clearFarTarget();
			executor.execute(locationStore::saveIfDirty);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		String key = event.getKey();
		if (!HcimGuideConfig.GROUP.equals(event.getGroup())
			|| key == null
			|| key.startsWith(HcimGuideConfig.COMPLETED_STEPS_KEY)
			|| "selectedGuide".equals(key)
			|| "guides".equals(key)
			|| "fullDbPrompted".equals(key))
		{
			return;
		}

		// take the hint arrow down immediately if it was just disabled
		clientThread.invokeLater(() ->
		{
			if (!config.enableHintArrow())
			{
				clearArrowOnClientThread();
			}
			else if (targetNpc != null)
			{
				client.setHintArrow(targetNpc);
				hintArrowSet = true;
			}
		});

		// rebuild rows so UI-affecting toggles apply without a guide refresh
		if ("showItemGrids".equals(key) || "dimCompletedSteps".equals(key)
			|| "autoCollapseCompleted".equals(key) || "panelTextSize".equals(key))
		{
			SwingUtilities.invokeLater(() -> panel.onConfigChanged());
		}

		// switching progress scope re-routes reads to the other store
		if ("perCharacterProgress".equals(key))
		{
			loadCompletedSteps();
			autoSuppressed.clear();
			SwingUtilities.invokeLater(() -> panel.refreshFromModel());
		}

		// take the world map marker down immediately when its toggle turns off
		if ("showWorldMapMarker".equals(key) && !config.showWorldMapMarker())
		{
			clientThread.invokeLater(this::removeMapMarker);
		}

		// bank tag feature: off -> full cleanup; on -> retag on the next cycle
		if ("bankTagIntegration".equals(key))
		{
			if (config.bankTagIntegration())
			{
				bankTagIntegration.invalidate();
			}
			else
			{
				bankTagIntegration.cleanup();
			}
		}

		// routing config: recompute on the next cycle; take the drawn path
		// down immediately when its toggle turns off
		if (key.startsWith("route"))
		{
			routeDirty = true;
			if (!config.routeUseShortestPath())
			{
				clientThread.invokeLater(pathfinder::clear);
			}
			if (!config.routeSuggestions())
			{
				routeSuggestion = null;
			}
		}

		// buffer size changed -> re-run the prefetch with the new depth
		if ("preloadNextBanks".equals(key))
		{
			lastPrefetchKey = null;
		}
	}

	// ------------------------------------------------------------------ auto-completion

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		updateTargetTracking();
		updateStepHighlights();

		if (++tickCounter % EVAL_INTERVAL_TICKS == 0)
		{
			evaluateAutoCompletion();
			syncBankTag();
			updateRouting();
			prefetchUpcomingIcons();
		}
	}

	// ------------------------------------------------------------------ routing & teleports

	/** HUD accessor; prebuilt string, null when there is nothing to suggest. */
	String getRouteSuggestion()
	{
		return routeSuggestion;
	}

	/**
	 * The point the player is currently heading to: the pinned target (live
	 * NPC position when in scene, last known location otherwise), or the
	 * next unchecked step's known location. Client thread.
	 */
	private WorldPoint currentObjective()
	{
		if (targetName != null)
		{
			NPC npc = targetNpc;
			if (npc != null)
			{
				return npc.getWorldLocation();
			}
			return farTarget;
		}
		return nextStepPoint;
	}

	/**
	 * Recompute the route suggestion and the Shortest Path hand-off. Runs
	 * every EVAL_INTERVAL_TICKS on the client thread, but the expensive part
	 * (candidate building) only runs when the player moved meaningfully, the
	 * objective changed, or item stock / config changed - otherwise this is
	 * a handful of comparisons.
	 */
	private void updateRouting()
	{
		boolean wantSuggest = config.routeSuggestions();
		boolean wantPath = config.routeUseShortestPath();
		if ((!wantSuggest && !wantPath) || currentGuide == null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			routeSuggestion = null;
			pathfinder.clear();
			return;
		}
		WorldPoint objective = currentObjective();
		if (objective == null)
		{
			routeSuggestion = null;
			pathfinder.clear();
			return;
		}
		if (wantPath)
		{
			pathfinder.setTarget(objective);
		}
		else
		{
			pathfinder.clear();
		}
		if (!wantSuggest)
		{
			routeSuggestion = null;
			return;
		}

		WorldPoint me = client.getLocalPlayer() != null
			? client.getLocalPlayer().getWorldLocation() : null;
		if (me == null)
		{
			routeSuggestion = null;
			return;
		}
		// already (nearly) there - suggesting a teleport would be noise
		if (RouteSuggester.dist(me.getX(), me.getY(), objective.getX(), objective.getY()) < 32)
		{
			routeSuggestion = null;
			return;
		}
		// cache guard: same objective, barely moved, nothing changed -> keep
		long stockRev = stockTracker.revision();
		if (!routeDirty
			&& objective.equals(lastRouteObjective)
			&& lastRoutePlayer != null
			&& RouteSuggester.dist(me.getX(), me.getY(), lastRoutePlayer.getX(), lastRoutePlayer.getY()) < 10
			&& stockRev == lastStockRevision)
		{
			return;
		}
		routeDirty = false;
		lastRouteObjective = objective;
		lastRoutePlayer = me;
		lastStockRevision = stockRev;

		RouteSuggester.Suggestion s = RouteSuggester.best(
			me.getX(), me.getY(), objective.getX(), objective.getY(), buildRouteCandidates());
		routeSuggestion = s == null
			? null
			: s.option.getName() + (s.banked ? " (in bank)" : "");
	}

	/** Teleports the player owns the items for, filtered by config. Client thread. */
	private List<RouteSuggester.Candidate> buildRouteCandidates()
	{
		String excludedRaw = config.routeExcluded();
		String[] excluded = excludedRaw == null || excludedRaw.trim().isEmpty()
			? new String[0]
			: excludedRaw.toLowerCase(java.util.Locale.ROOT).split(",");
		boolean includeBanked = config.routeIncludeBanked();

		List<RouteSuggester.Candidate> out = new ArrayList<>();
		nextOption:
		for (TeleportOption option : TeleportDirectory.ALL)
		{
			switch (option.getCategory())
			{
				case SPELL:
					if (!config.routeSpells())
					{
						continue;
					}
					break;
				case TAB:
					if (!config.routeTabs())
					{
						continue;
					}
					break;
				case JEWELRY:
					if (!config.routeJewelry())
					{
						continue;
					}
					break;
				default:
					if (!config.routeOther())
					{
						continue;
					}
					break;
			}
			String nameLow = option.getName().toLowerCase(java.util.Locale.ROOT);
			for (String ex : excluded)
			{
				String trimmed = ex.trim();
				if (!trimmed.isEmpty() && nameLow.contains(trimmed))
				{
					continue nextOption;
				}
			}
			// item availability: all needs carried -> carried candidate;
			// else all needs coverable with the bank counted in -> banked
			boolean carried = true;
			boolean withBank = true;
			for (TeleportOption.ItemNeed need : option.getNeeds())
			{
				int have = stockTracker.carried(need);
				if (have < need.getQty())
				{
					carried = false;
					if (!includeBanked || have + stockTracker.banked(need) < need.getQty())
					{
						withBank = false;
						break;
					}
				}
			}
			if (carried)
			{
				out.add(new RouteSuggester.Candidate(option, false));
			}
			else if (withBank && includeBanked)
			{
				out.add(new RouteSuggester.Candidate(option, true));
			}
		}
		return out;
	}

	/**
	 * Warm the item-icon cache for the active bank plus a small buffer of
	 * upcoming banks (configurable), so opening/scrolling to them never
	 * stalls on name-to-id resolution. Runs on the executor; triggered only
	 * when the active bank actually changes.
	 */
	private void prefetchUpcomingIcons()
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			return;
		}
		GuideBank activeBank = findActiveBank(guide);
		if (activeBank == null)
		{
			return;
		}
		String key = currentGuideId + "|" + activeBank.getId() + "|" + config.preloadNextBanks();
		if (key.equals(lastPrefetchKey))
		{
			return;
		}
		lastPrefetchKey = key;

		// collect the active bank + the next N banks' item requirements
		List<ItemReq> reqs = new ArrayList<>();
		int remaining = 1 + config.preloadNextBanks();
		boolean seen = false;
		outer:
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				if (!seen)
				{
					if (bank != activeBank)
					{
						continue;
					}
					seen = true;
				}
				for (GuideStep step : bank.getSteps())
				{
					StepCondition c = conditions.get(step.getKey());
					if (c != null && c.getType() == StepCondition.Type.ITEMS_IN_INVENTORY)
					{
						reqs.addAll(c.getItems());
					}
				}
				if (--remaining <= 0)
				{
					break outer;
				}
			}
		}
		if (reqs.isEmpty())
		{
			return;
		}
		final List<ItemReq> reqsCopy = reqs;
		executor.execute(() ->
		{
			if (active)
			{
				iconResolver.resolve(reqsCopy);
			}
		});
	}

	/**
	 * Keep the managed bank tag equal to the UNCHECKED item-steps of the
	 * CURRENT bank section only - completed steps drop out immediately and
	 * future banks are never tagged. Client thread; cheap (cached active
	 * bank, map lookups); actual tagging runs on the executor when the
	 * signature changes.
	 */
	private void syncBankTag()
	{
		if (!config.bankTagIntegration())
		{
			return;
		}
		Guide guide = currentGuide;
		GuideBank active = guide == null ? null : findActiveBank(guide);
		if (active == null)
		{
			bankTagIntegration.requestSync(null, null);
			return;
		}
		List<ItemReq> items = new ArrayList<>();
		StringBuilder signature = new StringBuilder(active.getId());
		for (GuideStep step : active.getSteps())
		{
			if (isCompleted(step.getKey()))
			{
				continue;
			}
			StepCondition c = conditions.get(step.getKey());
			if (c != null && c.getType() == StepCondition.Type.ITEMS_IN_INVENTORY)
			{
				items.addAll(c.getItems());
				signature.append('|').append(step.getKey());
			}
		}
		bankTagIntegration.requestSync(signature.toString(), items);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == BANK_GROUP_ID && config.bankTagIntegration())
		{
			bankTagIntegration.onBankOpened();
		}
	}

	/**
	 * Recompute which NPCs and ground items the current bank's unchecked steps
	 * reference, so the overlay can highlight them. Client thread only.
	 */
	private void updateStepHighlights()
	{
		Guide guide = currentGuide;
		boolean npcsWanted = config.highlightStepNpcs();
		boolean itemsWanted = config.highlightGroundItems();
		if (guide == null || (!npcsWanted && !itemsWanted))
		{
			stepNpcs = new ArrayList<>();
			groundHighlights = new ArrayList<>();
			return;
		}

		// wanted names come from precomputed maps - no regex on step text here
		Set<String> wantedNorm = new HashSet<>();
		List<ItemReq> itemReqs = new ArrayList<>();
		GuideBank active = findActiveBank(guide);
		if (active != null)
		{
			for (GuideStep step : active.getSteps())
			{
				if (isCompleted(step.getKey()))
				{
					continue;
				}
				if (npcsWanted)
				{
					String t = stepTargets.get(step.getKey());
					if (t != null)
					{
						wantedNorm.add(Names.normalize(t));
					}
				}
				if (itemsWanted)
				{
					StepCondition c = conditions.get(step.getKey());
					if (c != null && c.getType() == StepCondition.Type.ITEMS_IN_INVENTORY)
					{
						itemReqs.addAll(c.getItems());
					}
				}
			}
		}

		List<NPC> npcs = new ArrayList<>();
		if (!wantedNorm.isEmpty())
		{
			for (NPC npc : client.getTopLevelWorldView().npcs())
			{
				if (npc == null || npc.getName() == null)
				{
					continue;
				}
				// normalize the scene NPC's name ONCE, compare against each wanted name
				String npcNorm = Names.normalize(Text.removeTags(npc.getName()));
				for (String wanted : wantedNorm)
				{
					if (Names.matchNormalized(wanted, npcNorm))
					{
						npcs.add(npc);
						break;
					}
				}
			}
		}
		stepNpcs = npcs;

		List<GroundHighlight> ground = new ArrayList<>();
		if (!itemReqs.isEmpty())
		{
			for (GroundHighlight g : groundItems.values())
			{
				for (ItemReq req : itemReqs)
				{
					if (g.matches(req))
					{
						ground.add(g);
						break;
					}
				}
			}
		}
		groundHighlights = ground;
	}

	List<NPC> getStepNpcs()
	{
		return stepNpcs;
	}

	List<GroundHighlight> getGroundHighlights()
	{
		return groundHighlights;
	}

	private void evaluateAutoCompletion()
	{
		Guide guide = currentGuide;
		if (guide == null || !config.autoComplete())
		{
			return;
		}

		GuideBank activeBank = findActiveBank(guide);
		if (activeBank == null)
		{
			return;
		}

		List<GuideStep> justCompleted = new ArrayList<>();
		for (GuideStep step : activeBank.getSteps())
		{
			if (isCompleted(step.getKey()) || autoSuppressed.contains(step.getKey()))
			{
				continue;
			}
			StepCondition cond = conditions.get(step.getKey());
			if (cond != null && conditionMet(cond))
			{
				// suppress so this step is only ever auto-completed once; a later
				// manual untick then stays manual
				autoSuppressed.add(step.getKey());
				justCompleted.add(step);
			}
		}

		if (justCompleted.isEmpty())
		{
			return;
		}

		// single bulk update -> one JSON persist instead of one per step
		setCompletedBulk(justCompleted, true);

		if (config.notifyAutoComplete())
		{
			// cap the burst: after a progress import/wipe a whole bank can
			// complete in one evaluation - don't flood the chat box
			int shown = 0;
			for (GuideStep s : justCompleted)
			{
				if (shown == 3 && justCompleted.size() > 4)
				{
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Guide Overlay: ...and " + (justCompleted.size() - shown) + " more steps completed", null);
					break;
				}
				// removeTags: guide text must never smuggle <col>/<img> tags into chat
				String msg = "Guide Overlay: completed \"" + Text.removeTags(shorten(s.getText())) + "\"";
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
				shown++;
			}
		}

		// auto-advance: pin the next unchecked step that has a target NPC
		String advanceTarget = null;
		if (config.autoTrackNext())
		{
			GuideStep next = findNextTrackableStep(guide);
			if (next != null && !next.getKey().equals(pinnedStepKey))
			{
				pinnedStepKey = next.getKey();
				targetName = stepTargets.get(next.getKey());
				advanceTarget = targetName;
			}
		}

		final String finalTarget = advanceTarget;
		SwingUtilities.invokeLater(() ->
		{
			panel.refreshFromModel();
			if (finalTarget != null)
			{
				panel.onPinChanged(finalTarget);
			}
		});
	}

	/**
	 * First bank (in guide order) that still has an unchecked step. The result
	 * is cached because this is consulted every tick and by every bank header
	 * on the EDT; the cache is invalidated whenever completion state or the
	 * guide changes. A benign race (two threads recomputing the same value)
	 * is acceptable; the fields are volatile.
	 */
	private GuideBank findActiveBank(Guide guide)
	{
		if (!activeBankDirty && activeBankCacheGuide == guide)
		{
			return activeBankCache;
		}

		// clear the flag BEFORE walking: a concurrent invalidation during the
		// walk then lands after this write and forces the next call to recompute
		activeBankDirty = false;
		GuideBank found = null;
		outer:
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					if (!isCompleted(step.getKey()))
					{
						found = bank;
						break outer;
					}
				}
			}
		}
		activeBankCache = found;
		activeBankCacheGuide = guide;
		return found;
	}

	String getActiveBankId()
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			return null;
		}
		GuideBank bank = findActiveBank(guide);
		return bank == null ? null : bank.getId();
	}

	private GuideStep findNextTrackableStep(Guide guide)
	{
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					if (!isCompleted(step.getKey()) && stepTargets.containsKey(step.getKey()))
					{
						return step;
					}
				}
			}
		}
		return null;
	}

	/** Must be called on the client thread. */
	private boolean conditionMet(StepCondition cond)
	{
		switch (cond.getType())
		{
			case QUEST_STARTED:
			{
				QuestState state = cond.getQuest().getState(client);
				return state == QuestState.IN_PROGRESS || state == QuestState.FINISHED;
			}
			case QUEST_FINISHED:
				return cond.getQuest().getState(client) == QuestState.FINISHED;
			case SKILL_LEVEL:
				return client.getRealSkillLevel(cond.getSkill()) >= cond.getLevel();
			case ITEMS_IN_INVENTORY:
			{
				InventorySnapshot snap = inventory;
				for (ItemReq req : cond.getItems())
				{
					if (snap.countOf(req) < req.getQuantity())
					{
						return false;
					}
				}
				return true;
			}
			default:
				return false;
		}
	}

	private static String shorten(String s)
	{
		return s.length() <= 60 ? s : s.substring(0, 57) + "...";
	}

	// ------------------------------------------------------------------ target tracking

	/** Pin a step: track its NPC target with hint arrow + outline. Pass null to unpin. */
	void pinStep(GuideStep step)
	{
		if (step == null)
		{
			pinnedStepKey = null;
			targetName = null;
			clientThread.invokeLater(() ->
			{
				clearArrowOnClientThread();
				targetNpc = null;
				clearFarTarget();
			});
			return;
		}
		pinnedStepKey = step.getKey();
		targetName = stepTargets.get(step.getKey());
	}

	String getPinnedStepKey()
	{
		return pinnedStepKey;
	}

	NPC getTargetNpc()
	{
		return targetNpc;
	}

	private void clearArrowOnClientThread()
	{
		if (hintArrowSet)
		{
			client.clearHintArrow();
			hintArrowSet = false;
		}
		lastArrowPoint = null;
	}

	private void updateTargetTracking()
	{
		String name = targetName;
		if (name == null)
		{
			if (targetNpc != null || hintArrowSet)
			{
				clearArrowOnClientThread();
				targetNpc = null;
			}
			farTarget = null;
			// nothing pinned: keep the world map useful by marking the NEXT
			// unchecked step's target (config-gated; compass/arrow stay pinned-only)
			updateNextStepMarker();
			return;
		}

		// normalize the pinned name once, not once per scene NPC
		final String nameNorm = Names.normalize(name);
		NPC found = null;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getName() == null)
			{
				continue;
			}
			if (Names.matchNormalized(nameNorm, Names.normalize(Text.removeTags(npc.getName()))))
			{
				found = npc;
				break;
			}
		}

		if (found != targetNpc)
		{
			targetNpc = found;
			if (found != null && config.enableHintArrow())
			{
				client.setHintArrow(found);
				hintArrowSet = true;
				lastArrowPoint = null;
			}
			else
			{
				clearArrowOnClientThread();
			}
			final boolean nearby = found != null;
			SwingUtilities.invokeLater(() -> panel.setTargetStatus(name, nearby));
		}

		if (found != null)
		{
			clearFarTarget();
			return;
		}

		// target not in the scene: fall back to the coordinate database
		WorldPoint known = locationStore.lookup(name);
		if (known == null)
		{
			clearFarTarget();
			return;
		}
		LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), known);
		if (lp != null)
		{
			// stored location is inside the loaded scene (e.g. behind a wall or
			// on another plane): use the native hint arrow on the tile
			clearFarTarget();
			if (config.enableHintArrow() && !known.equals(lastArrowPoint))
			{
				client.setHintArrow(known);
				hintArrowSet = true;
				lastArrowPoint = known;
			}
		}
		else if (!known.equals(farTarget))
		{
			farTarget = known;
			updateMapMarker(known, name);
		}
	}

	/** Last known location of the pinned target when it's beyond the loaded scene. Client thread. */
	WorldPoint getFarTarget()
	{
		return farTarget;
	}

	private void clearFarTarget()
	{
		farTarget = null;
		removeMapMarker();
	}

	/**
	 * With nothing pinned, mark the next unchecked step's known target
	 * location so the world map always answers "where do I go next?".
	 * Client thread only; cheap (cached active bank + map lookups).
	 */
	private void updateNextStepMarker()
	{
		// the next step's location feeds BOTH the world map marker and the
		// routing objective, so it's computed whenever either feature wants it
		boolean routingWanted = config.routeSuggestions() || config.routeUseShortestPath();
		if ((!config.showNextStepOnMap() && !routingWanted) || currentGuide == null)
		{
			nextStepPoint = null;
			removeMapMarker();
			return;
		}
		Guide guide = currentGuide;
		GuideBank active = findActiveBank(guide);
		if (active != null)
		{
			for (GuideStep step : active.getSteps())
			{
				if (isCompleted(step.getKey()))
				{
					continue;
				}
				String target = stepTargets.get(step.getKey());
				if (target == null)
				{
					continue; // next step has no locatable target -> look further down
				}
				WorldPoint known = locationStore.lookup(target);
				if (known != null)
				{
					nextStepPoint = known;
					if (config.showNextStepOnMap())
					{
						setMarker(known, "next step: " + target);
					}
					else
					{
						removeMapMarker();
					}
					return;
				}
				// first targeted step has no known location - don't mislead by
				// marking a LATER step's target instead
				nextStepPoint = null;
				removeMapMarker();
				return;
			}
		}
		nextStepPoint = null;
		removeMapMarker();
	}

	/** Marker icon is decoded once, not per target change. */
	private static final BufferedImage MAP_MARKER_ICON =
		ImageUtil.loadImageResource(HcimGuidePlugin.class, "panel_icon.png");

	private void updateMapMarker(WorldPoint point, String name)
	{
		setMarker(point, name);
	}

	/** Idempotent: same point -> no churn; new point -> marker replaced. */
	private void setMarker(WorldPoint point, String name)
	{
		if (point != null && point.equals(markerPoint) && mapMarker != null)
		{
			return;
		}
		removeMapMarker();
		if (point == null || !config.showWorldMapMarker())
		{
			return;
		}
		WorldMapPoint marker = new WorldMapPoint(point, MAP_MARKER_ICON);
		marker.setTooltip("Guide Overlay: " + name);
		mapMarker = marker;
		markerPoint = point;
		worldMapPointManager.add(marker);
	}

	private void removeMapMarker()
	{
		if (mapMarker != null)
		{
			worldMapPointManager.remove(mapMarker);
			mapMarker = null;
		}
		markerPoint = null;
	}
}
