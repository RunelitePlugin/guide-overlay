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
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.QuestState;
import net.runelite.api.TileItem;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
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
	private net.runelite.client.game.SpriteManager spriteManager;

	@Inject
	private BankStockTracker stockTracker;

	@Inject
	private PathfinderIntegration pathfinder;

	@Inject
	private StepNavOverlay stepNavOverlay;

	@Inject
	private DialogOptionOverlay dialogOptionOverlay;

	@Inject
	private net.runelite.client.input.KeyManager keyManager;

	@Inject
	private net.runelite.client.input.MouseManager mouseManager;

	/** Bank interface group id - the symbolic gameval constant, not a magic 12. */
	private static final int BANK_GROUP_ID = InterfaceID.BANKMAIN;

	private NavigationButton navButton;
	private HcimGuidePanel panel;

	private final Set<String> completedSteps = new HashSet<>();

	/**
	 * Steps the player marked as skipped (optional detours they chose not to
	 * do): excluded from progress counts, never block the active-bank cursor,
	 * never auto-complete, and contribute nothing to bank tags/highlights.
	 * Guarded by synchronized (skippedSteps); persisted like completedSteps.
	 */
	private final Set<String> skippedSteps = new HashSet<>();

	/**
	 * False after shutDown: executor tasks and fetch callbacks queued before a
	 * disable check this so they can't repopulate state (or write config /
	 * touch the removed panel) after the plugin was turned off.
	 */
	private volatile boolean active;
	private volatile Guide currentGuide;
	private volatile String currentGuideId = GuideRegistry.BUILTIN_ID;
	private volatile Map<String, StepCondition> conditions = Collections.emptyMap();
	/**
	 * Step key -&gt; item requirements to DISPLAY for that step (item grids, HUD
	 * pictures, bank tag). Superset of the ITEMS_IN_INVENTORY conditions: JSON
	 * guides' "(Items: ...)" lists appear here too, without ever becoming an
	 * auto-completion condition (holding the items isn't what completes them).
	 */
	private volatile Map<String, List<ItemReq>> stepItems = Collections.emptyMap();
	/** Step key -&gt; dialogue-choice sequence parsed from "(2,1)" notation. */
	private volatile Map<String, int[]> dialogSequences = Collections.emptyMap();
	/** Step key -&gt; normalized NPC-name candidates the sequence belongs to. */
	private volatile Map<String, List<String>> dialogNpcs = Collections.emptyMap();
	/** Step key -&gt; normalized scene-object words for object-driven dialogue. */
	private volatile Map<String, Set<String>> dialogObjectWords = Collections.emptyMap();
	/** Step key -&gt; extracted NPC target name; precomputed at import (never re-extracted per tick). */
	private volatile Map<String, String> stepTargets = Collections.emptyMap();
	/** Normalized target names of the current guide - bounds what the location store learns. */
	private volatile Set<String> targetNamesNorm = Collections.emptySet();
	private volatile InventorySnapshot inventory = InventorySnapshot.EMPTY;

	// far-target pointing (client thread only)
	private WorldPoint farTarget;
	private WorldMapPoint mapMarker;
	private WorldPoint markerPoint;
	private String markerName;
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
	/**
	 * Scene scans are only needed when guide/progress/config or scene contents
	 * change. A periodic refresh is retained as a safety net for NPC transforms
	 * that do not arrive as spawn/despawn events.
	 */
	private volatile boolean stepHighlightsDirty = true;
	private static final int HIGHLIGHT_SAFETY_REFRESH_TICKS = 20;

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

	// ------- current-step item pictures for the HUD (see updateHudItems)

	/** Immutable items+ids pair so the HUD never reads a mismatched snapshot. */
	static final class HudItems
	{
		final List<ItemReq> items;
		final int[] ids;

		HudItems(List<ItemReq> items, int[] ids)
		{
			this.items = items;
			this.ids = ids;
		}
	}

	private volatile HudItems hudItems;
	private volatile String hudItemsKey;
	/** Bumps on every current-step change; stale resolutions are dropped. */
	private final java.util.concurrent.atomic.AtomicInteger hudItemsGen =
		new java.util.concurrent.atomic.AtomicInteger();

	// ------- step navigation (clickable arrows + keybinds)

	private final net.runelite.client.util.HotkeyListener nextStepHotkey =
		new net.runelite.client.util.HotkeyListener(() -> config.nextStepKeybind())
	{
		@Override
		public void hotkeyPressed()
		{
			if (config.navKeybindsEnabled())
			{
				navigateStep(true);
			}
		}
	};

	private final net.runelite.client.util.HotkeyListener prevStepHotkey =
		new net.runelite.client.util.HotkeyListener(() -> config.prevStepKeybind())
	{
		@Override
		public void hotkeyPressed()
		{
			if (config.navKeybindsEnabled())
			{
				navigateStep(false);
			}
		}
	};

	/** True between a consumed arrow press and its release, so both are eaten. */
	private boolean navPressConsumed;

	/**
	 * One-shot guard for centering the floating arrows (reset when the arrow
	 * mode config changes, so re-selecting Floating re-checks). Volatile:
	 * written from the config-change dispatch thread, read on the client thread.
	 */
	private volatile boolean navCenterChecked;

	// ------- trip-ready + section-complete confirmations (client thread)

	/** A pleasant "success" chime from the game's own sound effects. */
	private static final int SUCCESS_SOUND = net.runelite.api.SoundEffectID.GE_ADD_OFFER_DINGALING;
	/** Distinct from SUCCESS_SOUND so "ready to leave" never sounds like "done". */
	private static final int TRIP_READY_SOUND = net.runelite.api.SoundEffectID.GE_COIN_TINKLE;

	private boolean tripReady;
	private String tripReadySoundedBank;
	/** Bank evaluated last tick: the first look at a NEW bank never sounds. */
	private String tripReadyEvalBank;
	/** Read by the HUD on the same (client) thread, volatile for safety. */
	private volatile boolean tripReadyVisible;

	/** Active bank id last tick, for detecting section completion. */
	private String prevActiveBankId;
	private Guide prevActiveBankGuide;
	/**
	 * Set whenever the progress STORE is swapped wholesale under an unchanged
	 * guide model (character switch, progress import, undo): the next tick
	 * re-baselines section-complete tracking instead of "noticing" the jump.
	 */
	private volatile boolean sectionNoticeResetPending;

	// ------- step-object highlighting (all client thread)

	/**
	 * Whitelisted interactable objects currently in the scene (ladders,
	 * altars, doors, ...), by normalized name. Maintained from spawn events;
	 * cleared on scene load. Only whitelist matches are stored, so this holds
	 * dozens of entries, not the whole scene.
	 */
	private final Map<net.runelite.api.TileObject, String> sceneObjects = new HashMap<>();
	private List<net.runelite.api.TileObject> objectHighlights = new ArrayList<>();
	/** Object id -> normalized whitelisted name ("" = not whitelisted). */
	private final Map<Integer, String> objectNameCache = new HashMap<>();

	// ------- dialogue option guidance (client thread)

	/** The step whose sequence is being followed (pinned wins, else current). */
	private String dialogStepKey;
	private int[] dialogSeq;
	/** Position within dialogSeq: how many option menus were already answered. */
	private int dialogPos;
	/** Tick a dialogue widget was last seen open, for the conversation timeout. */
	private int lastDialogActivityTick = -1;
	/**
	 * NO dialogue widget open for this many ticks = conversation over. Short
	 * on purpose: while ANY dialogue widget (options menu, NPC/player/object
	 * text) is showing, activity refreshes every tick, so reading slowly can
	 * never reset the sequence - only actually leaving the conversation can.
	 */
	private static final int DIALOG_RESET_TICKS = 4;
	/** 1-based option the overlay should outline right now; -1 = none. */
	private volatile int dialogHighlightOption = -1;
	/**
	 * Normalized names of who the CURRENT conversation is with: seeded by the
	 * NPC or scene object the player clicked, extended by every speaker name
	 * shown on the NPC dialogue widget. The highlight only draws when one of
	 * these matches the guided step's own NPC/object - a step that names
	 * nobody, or a chat with the wrong NPC, gets NO highlight (fail closed;
	 * Quest Helper covers in-quest dialogue). Cleared when a new interaction
	 * starts or the conversation times out. Client thread only.
	 */
	private final Set<String> conversationNames = new HashSet<>();
	/** Bounds conversationNames against pathological many-speaker cutscenes. */
	private static final int MAX_CONVERSATION_NAMES = 8;
	/** Strips "(level-42)" from clicked-menu target text before matching. */
	private static final java.util.regex.Pattern LEVEL_SUFFIX =
		java.util.regex.Pattern.compile("(?i)\\s*\\(level[-\\s]*[0-9]+\\)\\s*$");
	/** Tick of the last NPC/object interaction click that seeded the names. */
	private int lastInteractionClickTick = -1;
	/**
	 * The click seed must survive the walk TO the target (no dialogue open
	 * yet), or menu-first dialogs would never know their partner. Cleared
	 * only once the conversation has also been quiet past this grace.
	 */
	private static final int CLICK_SEED_GRACE_TICKS = 50;
	/**
	 * An interaction click dismisses any option menu still on screen WITHOUT
	 * answering it - that close must not advance the sequence. Set on click,
	 * cleared when the next menu loads or the stale one's close is skipped.
	 */
	private boolean pendingInteractionClick;
	/**
	 * Whether the conversation on screen matched the guided step when last
	 * evaluated - the widget-close advance gate. Kept up to date even while
	 * the highlight config is off, so re-enabling mid-conversation resumes
	 * at the RIGHT menu instead of a stale position.
	 */
	private boolean dialogAdvanceArmed;

	private final net.runelite.client.input.MouseAdapter navMouse =
		new net.runelite.client.input.MouseAdapter()
	{
		@Override
		public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
		{
			// Alt+drag is overlay repositioning - never intercept it
			if (e.getButton() != java.awt.event.MouseEvent.BUTTON1 || e.isAltDown())
			{
				return e;
			}
			int dir = hitNavArrow(e.getPoint());
			if (dir != 0)
			{
				navPressConsumed = true;
				e.consume();
				navigateStep(dir > 0);
			}
			return e;
		}

		@Override
		public java.awt.event.MouseEvent mouseReleased(java.awt.event.MouseEvent e)
		{
			if (navPressConsumed)
			{
				navPressConsumed = false;
				e.consume();
			}
			return e;
		}

		@Override
		public java.awt.event.MouseEvent mouseClicked(java.awt.event.MouseEvent e)
		{
			if (e.getButton() == java.awt.event.MouseEvent.BUTTON1 && !e.isAltDown()
				&& hitNavArrow(e.getPoint()) != 0)
			{
				e.consume();
			}
			return e;
		}
	};

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
		overlayManager.add(stepNavOverlay);
		overlayManager.add(dialogOptionOverlay);
		keyManager.registerKeyListener(nextStepHotkey);
		keyManager.registerKeyListener(prevStepHotkey);
		mouseManager.registerMouseListener(navMouse);

		// scrub a bank tag left behind by a crashed session if the feature is off
		// (when it's on, the first sync cycle rebuilds the tag anyway)
		if (!config.bankTagIntegration())
		{
			bankTagIntegration.cleanup();
		}

		// the REAL inventory stone background for item grids, from the
		// player's own game files (async; painted fallback shows until then)
		spriteManager.getSpriteAsync(net.runelite.api.SpriteID.FIXED_MODE_SIDE_PANEL_BACKGROUND, 0,
			img ->
			{
				ItemGridPanel.setInventoryBackground(img);
				SwingUtilities.invokeLater(() ->
				{
					if (panel != null)
					{
						panel.repaint();
					}
				});
			});

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
		overlayManager.remove(stepNavOverlay);
		overlayManager.remove(dialogOptionOverlay);
		keyManager.unregisterKeyListener(nextStepHotkey);
		keyManager.unregisterKeyListener(prevStepHotkey);
		mouseManager.unregisterMouseListener(navMouse);
		navPressConsumed = false;
		clientToolbar.removeNavigation(navButton);
		executor.execute(locationStore::saveIfDirty);
		bankTagIntegration.cleanup();

		// the plugin instance is reused on re-enable: clear all transient state,
		// including the guide model so a deleted snapshot can't leave stale
		// highlighting/auto-completion running after re-enable
		pinnedStepKey = null;
		targetName = null;
		autoSuppressed.clear();
		synchronized (skippedSteps)
		{
			skippedSteps.clear();
		}
		synchronized (undoLock)
		{
			undoCompleted = null;
			undoSkipped = null;
			undoGuideId = null;
			undoLabel = null;
		}
		tripReadyVisible = false;
		inventory = InventorySnapshot.EMPTY;
		currentGuide = null;
		conditions = Collections.emptyMap();
		stepItems = Collections.emptyMap();
		dialogSequences = Collections.emptyMap();
		dialogNpcs = Collections.emptyMap();
		dialogObjectWords = Collections.emptyMap();
		dialogHighlightOption = -1;
		clearHudItems();
		stepTargets = Collections.emptyMap();
		targetNamesNorm = Collections.emptySet();
		activeBankCache = null;
		activeBankCacheGuide = null;
		activeBankDirty = true;
		stepHighlightsDirty = true;
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
			sceneObjects.clear();
			objectHighlights = new ArrayList<>();
			objectNameCache.clear();
			tripReady = false;
			tripReadySoundedBank = null;
			tripReadyEvalBank = null;
			dialogStepKey = null;
			dialogSeq = null;
			dialogPos = 0;
			lastDialogActivityTick = -1;
			conversationNames.clear();
			lastInteractionClickTick = -1;
			pendingInteractionClick = false;
			dialogAdvanceArmed = false;
			prevActiveBankId = null;
			prevActiveBankGuide = null;
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
		stepItems = Collections.emptyMap();
		dialogSequences = Collections.emptyMap();
		dialogNpcs = Collections.emptyMap();
		dialogObjectWords = Collections.emptyMap();
		dialogHighlightOption = -1;
		clearHudItems();
		stepTargets = Collections.emptyMap();
		targetNamesNorm = Collections.emptySet();
		activeBankCache = null;
		activeBankCacheGuide = null;
		activeBankDirty = true;
		stepHighlightsDirty = true;

		currentGuideId = guideId;
		configManager.setConfiguration(HcimGuideConfig.GROUP, "selectedGuide", guideId);
		loadCompletedSteps();
		loadSkippedSteps();
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
			SwingUtilities.invokeLater(() -> panel.setStatus("Importing guide from file..."));
			importFileOnExecutor(file, entry.getId(), true);
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
					// skipped steps stay skipped - completing them would just
					// resurface them in the counts
					if (!isSkipped(step.getKey()))
					{
						before.add(step);
					}
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
		snapshotBeforeBulk("complete every previous step");
		setCompletedBulk(before, true);
	}

	/**
	 * Mirror of {@link #completeAllStepsBefore}: un-checks every step AFTER
	 * the given step in guide order, across banks and chapters, in one bulk
	 * persist. The step itself keeps its state. Un-checking marks each step
	 * manually-unticked (auto-suppressed), so auto-completion won't instantly
	 * re-tick them - exactly like unticking each checkbox by hand.
	 */
	void clearAllStepsAfter(String stepKey)
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			return;
		}
		List<GuideStep> after = new ArrayList<>();
		boolean found = false;
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					// only steps that are ACTUALLY completed: bulk-unticking
					// marks each one auto-suppressed, and suppressing thousands
					// of never-completed steps would silently turn off
					// auto-completion for the rest of the guide this session
					if (found && isCompleted(step.getKey()))
					{
						after.add(step);
					}
					else if (!found && step.getKey().equals(stepKey))
					{
						found = true;
					}
				}
			}
		}
		// anchor not in the CURRENT guide (re-import or switch mid-dialog):
		// do nothing rather than wipe the wrong guide's progress
		if (!found)
		{
			SwingUtilities.invokeLater(() ->
				panel.setStatus("Guide changed while confirming - nothing was cleared"));
			return;
		}
		snapshotBeforeBulk("clear every later step");
		setCompletedBulk(after, false);
	}

	/**
	 * One-click catch-up for the WHOLE guide: on the client thread, evaluate
	 * every step's auto-completion condition against the account's actual
	 * quest log and skill levels and complete the ones the game confirms.
	 * Item conditions are deliberately excluded - inventories are transient,
	 * so "holding the items right now" proves nothing guide-wide. Undoable
	 * via the bulk-undo snapshot.
	 */
	void syncToAccount()
	{
		clientThread.invokeLater(() ->
		{
			if (!active)
			{
				return;
			}
			Guide guide = currentGuide;
			if (guide == null)
			{
				return;
			}
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				SwingUtilities.invokeLater(() ->
					panel.setStatus("Log in first - syncing reads your live quest log and skills"));
				return;
			}
			List<GuideStep> verified = new ArrayList<>();
			for (GuideEpisode ep : guide.getEpisodes())
			{
				for (GuideBank bank : ep.getBanks())
				{
					for (GuideStep step : bank.getSteps())
					{
						String key = step.getKey();
						if (isStepDone(key))
						{
							continue;
						}
						StepCondition cond = conditions.get(key);
						if (cond == null || cond.getType() == StepCondition.Type.ITEMS_IN_INVENTORY)
						{
							continue;
						}
						try
						{
							if (conditionMet(cond))
							{
								verified.add(step);
							}
						}
						catch (Exception ignored)
						{
							// one unreadable quest state must not abort the sync
						}
					}
				}
			}
			if (verified.isEmpty())
			{
				SwingUtilities.invokeLater(() ->
					panel.setStatus("Already in sync - no verifiable steps to add"));
				return;
			}
			snapshotBeforeBulk("account sync");
			setCompletedBulk(verified, true);
			final int n = verified.size();
			SwingUtilities.invokeLater(() ->
			{
				if (active && panel != null)
				{
					panel.refreshFromModel();
					panel.setStatus("Synced " + n + " step" + (n == 1 ? "" : "s")
						+ " from your quest log and skills");
				}
			});
		});
	}

	/** Explicit import from a local wikitext file chosen by the user, into the selected guide. */
	void importFromFile(java.io.File file)
	{
		final String guideId = currentGuideId;
		SwingUtilities.invokeLater(() -> panel.setStatus("Importing guide from file..."));
		executor.execute(() -> importFileOnExecutor(file, guideId, false));
	}

	/** Runs on the plugin executor. New file-only entries are rolled back on failure. */
	private void importFileOnExecutor(java.io.File file, String guideId, boolean removeEntryOnFailure)
	{
		if (!active)
		{
			if (removeEntryOnFailure)
			{
				guideRegistry.remove(guideId);
			}
			return;
		}
		try
		{
			if (file == null || !file.isFile())
			{
				throw new IllegalArgumentException("Selected file no longer exists");
			}
			String text = readLocalGuideFile(file);
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
			if (removeEntryOnFailure)
			{
				guideRegistry.remove(guideId);
				if (guideId.equals(currentGuideId))
				{
					selectGuideInternal(GuideRegistry.BUILTIN_ID, false);
				}
				SwingUtilities.invokeLater(() -> panel.setGuides(guideRegistry.list(), currentGuideId));
			}
			SwingUtilities.invokeLater(() -> panel.setStatus("Import failed: " + e.getMessage()));
		}
	}

	private static String readLocalGuideFile(java.io.File file) throws java.io.IOException
	{
		final int maxBytes = 10 * 1024 * 1024;
		try (java.io.InputStream in = java.nio.file.Files.newInputStream(file.toPath()))
		{
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) > 0)
			{
				out.write(buffer, 0, n);
				if (out.size() > maxBytes)
				{
					throw new IllegalArgumentException("File too large (over 10MB) - guides are plain wikitext");
				}
			}
			return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
		}
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
		final String destinationGuideId = currentGuideId;
		final Guide destinationGuide = currentGuide;
		// decode (gzip/base64, possibly hostile input) off the EDT
		executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				if (destinationGuide == null)
				{
					// imported keys are validated against the loaded guide's
					// steps, so a guide must be loaded first
					SwingUtilities.invokeLater(() ->
						panel.setStatus("Import the guide first, then import its progress"));
					return;
				}
				if (!destinationGuideId.equals(currentGuideId))
				{
					SwingUtilities.invokeLater(() ->
						panel.setStatus("Guide changed before progress could be imported - nothing changed"));
					return;
				}
				ProgressCodec.Decoded decoded = ProgressCodec.decode(code);
				final String note = decoded.guideId != null && !decoded.guideId.equals(destinationGuideId)
					? " (note: exported from a different guide)"
					: "";
				Set<String> validKeys = new HashSet<>();
				for (GuideEpisode episode : destinationGuide.getEpisodes())
				{
					for (GuideBank bank : episode.getBanks())
					{
						for (GuideStep step : bank.getSteps())
						{
							validKeys.add(step.getKey());
						}
					}
				}
				Set<String> imported = new HashSet<>(decoded.keys);
				imported.retainAll(validKeys);
				int ignored = decoded.keys.size() - imported.size();
				// Decoding and validation run off the EDT and may take long enough for
				// the user to switch guides. Re-check immediately before mutating the
				// shared progress set so another guide can never receive this import.
				if (destinationGuide != currentGuide || !destinationGuideId.equals(currentGuideId))
				{
					SwingUtilities.invokeLater(() ->
						panel.setStatus("Guide changed during progress import - nothing changed"));
					return;
				}
				snapshotBeforeBulk("progress import");
				sectionNoticeResetPending = true; // store swap, not gameplay
				synchronized (completedSteps)
				{
					completedSteps.clear();
					completedSteps.addAll(imported);
				}
				autoSuppressed.clear();
				activeBankDirty = true;
				stepHighlightsDirty = true;
				persistCompletedSteps();
				SwingUtilities.invokeLater(() ->
				{
					panel.refreshFromModel();
					panel.setStatus("Progress imported: " + imported.size() + " steps"
						+ (ignored > 0 ? " (ignored " + ignored + " unknown keys)" : "") + note);
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
		Map<String, List<ItemReq>> items = new HashMap<>();
		Map<String, int[]> dialogSeqs = new HashMap<>();
		Map<String, List<String>> dialogNpcMap = new HashMap<>();
		Map<String, Set<String>> dialogObjMap = new HashMap<>();
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
					// items to DISPLAY for this step: withdraw/gather condition
					// items, or a JSON guide's "(Items: ...)" list (display-only,
					// never an auto-completion condition)
					if (c != null && c.getType() == StepCondition.Type.ITEMS_IN_INVENTORY)
					{
						items.put(step.getKey(), c.getItems());
					}
					else
					{
						List<ItemReq> suffix = ItemListParser.parseItemsSuffix(step.getText());
						if (suffix != null)
						{
							items.put(step.getKey(), suffix);
						}
					}
					int[] seq = DialogSequenceParser.extract(step.getText());
					if (seq != null)
					{
						dialogSeqs.put(step.getKey(), seq);
						// who the sequence's conversation is with: the name
						// written right before the notation plus the step's
						// "Talk to X" target - either may be null or wrong
						// ("Client of Kourend"), so keep every candidate
						List<String> npcCandidates = new ArrayList<>();
						String before = DialogSequenceParser.npcBefore(step.getText());
						if (before != null && !Names.normalize(before).isEmpty())
						{
							npcCandidates.add(Names.normalize(before));
						}
						String verbTarget = TargetExtractor.extract(step.getText());
						if (verbTarget != null && !Names.normalize(verbTarget).isEmpty()
							&& !npcCandidates.contains(Names.normalize(verbTarget)))
						{
							npcCandidates.add(Names.normalize(verbTarget));
						}
						String travelTarget = TargetExtractor.extractSecondary(step.getText());
						if (travelTarget != null && !Names.normalize(travelTarget).isEmpty()
							&& !npcCandidates.contains(Names.normalize(travelTarget)))
						{
							npcCandidates.add(Names.normalize(travelTarget));
						}
						if (!npcCandidates.isEmpty())
						{
							dialogNpcMap.put(step.getKey(), npcCandidates);
						}
						else
						{
							// object words only when the step names NO NPC:
							// "talk to Reldo" steps that merely pass stairs
							// must not light up the staircase's climb menu
							Set<String> objWords = TargetExtractor.objectWordsIn(step.getText());
							if (!objWords.isEmpty())
							{
								dialogObjMap.put(step.getKey(), objWords);
							}
						}
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
		stepItems = items;
		dialogSequences = dialogSeqs;
		dialogNpcs = dialogNpcMap;
		dialogObjectWords = dialogObjMap;
		stepTargets = targets;
		targetNamesNorm = namesNorm;
		currentGuide = guide;
		hudItemsKey = null; // re-derive the HUD's current-step items
		boolean restoredNotesProgress = migrateLegacyNotesProgress(guide);
		activeBankDirty = true;
		stepHighlightsDirty = true;
		routeDirty = true;      // new model -> new objective/candidates
		lastPrefetchKey = null; // re-warm icons for the new active bank
		int banks = guide.numberedBanks();
		String structure = banks > 0
			? banks + " banks, " + guide.totalSteps() + " steps"
			: guide.totalSections() + " sections, " + guide.totalSteps() + " steps";
		String finalStatus = status + " — " + structure;
		// warn ONLY when the wikitext fallback dumped steps into "Notes"
		// sections - JSON/generic guides legitimately have no numbered banks
		// and must not see a scary warning
		boolean notesFallback = false;
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank b : ep.getBanks())
			{
				if (b.getId().endsWith("-notes"))
				{
					notesFallback = true;
					break;
				}
			}
		}
		if (banks == 0 && notesFallback && guide.getEpisodes().size() > 1)
		{
			finalStatus += " (warning: no numbered bank markers were recognized)";
		}
		else if (restoredNotesProgress)
		{
			finalStatus += " (previous Notes progress restored)";
		}
		String displayStatus = finalStatus;
		SwingUtilities.invokeLater(() -> panel.setGuide(guide, displayStatus));
	}

	private boolean migrateLegacyNotesProgress(Guide guide)
	{
		boolean migrated;
		synchronized (completedSteps)
		{
			migrated = ProgressKeyMigration.migrateLegacyNotes(guide, completedSteps);
			// keys whose step TEXT a parser fix changed (e.g. the JSON parser's
			// run-joining fix) replay through the guide's explicit key map
			migrated |= ProgressKeyMigration.migrateLegacyKeys(guide, completedSteps);
			// paragraphs the parser now splits into several steps: a completed
			// paragraph key marks every split child complete (AFTER the 1:1
			// replay - oldest keys chain through the split map's parents)
			migrated |= ProgressKeyMigration.migrateSplitKeys(guide, completedSteps);
		}
		if (migrated)
		{
			persistCompletedSteps();
		}
		boolean skippedMigrated;
		synchronized (skippedSteps)
		{
			skippedMigrated = ProgressKeyMigration.migrateSplitKeys(guide, skippedSteps);
		}
		if (skippedMigrated)
		{
			persistSkippedSteps();
		}
		return migrated;
	}

	StepCondition getCondition(String stepKey)
	{
		return conditions.get(stepKey);
	}

	/** Items to display for a step (grids, HUD, bank tag), or null. */
	List<ItemReq> getStepItems(String stepKey)
	{
		return stepItems.get(stepKey);
	}

	/** True when a guide model is loaded (overlay gate; volatile read). */
	boolean hasGuideLoaded()
	{
		return currentGuide != null;
	}

	/** The HUD's current-step items+ids snapshot, or null. */
	HudItems getHudStepItems()
	{
		return hudItems;
	}

	/**
	 * Step navigation (arrow buttons and keybinds). Forward marks the current
	 * step - the first unchecked step in guide order - complete and thereby
	 * advances; backward un-checks the most recently completed step before
	 * that cursor and moves back to it. Un-checking suppresses re-auto-
	 * completion (manual control always wins), exactly like unticking the
	 * checkbox. Pure checklist bookkeeping: no game input is ever synthesized.
	 * Called from the AWT input thread; all state it touches is thread-safe.
	 */
	void navigateStep(boolean forward)
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			return;
		}
		GuideStep target = null;
		if (forward)
		{
			outer:
			for (GuideEpisode ep : guide.getEpisodes())
			{
				for (GuideBank bank : ep.getBanks())
				{
					for (GuideStep step : bank.getSteps())
					{
						if (!isStepDone(step.getKey()))
						{
							target = step;
							break outer;
						}
					}
				}
			}
			if (target == null)
			{
				return; // whole guide complete - nothing to advance to
			}
			setCompleted(target.getKey(), true);
		}
		else
		{
			// the completed step nearest BEFORE the cursor, in guide order
			// (out-of-order completions after the cursor are left alone)
			GuideStep lastDone = null;
			outer:
			for (GuideEpisode ep : guide.getEpisodes())
			{
				for (GuideBank bank : ep.getBanks())
				{
					for (GuideStep step : bank.getSteps())
					{
						if (!isStepDone(step.getKey()))
						{
							break outer;
						}
						if (isCompleted(step.getKey()))
						{
							lastDone = step; // skipped steps are stepped OVER, not unticked
						}
					}
				}
			}
			if (lastDone == null)
			{
				return; // nothing completed yet - nowhere to go back to
			}
			setCompleted(lastDone.getKey(), false);
		}
		SwingUtilities.invokeLater(() ->
		{
			if (active && panel != null)
			{
				panel.refreshFromModel();
			}
		});
	}

	/** Pin the next unchecked step that has a trackable NPC target. */
	void pinNextTrackableStep()
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			return;
		}
		GuideStep next = findNextTrackableStep(guide);
		if (next == null)
		{
			return;
		}
		pinStep(next);
		final String target = stepTargets.get(next.getKey());
		SwingUtilities.invokeLater(() ->
		{
			if (active && panel != null)
			{
				panel.onPinChanged(target);
			}
		});
	}

	/**
	 * Hit test for the nav arrow buttons, whichever host is active. Called on
	 * the AWT input thread; overlays publish their rects volatilely and clear
	 * them whenever they aren't rendering, so a stale frame can't eat clicks.
	 */
	private int hitNavArrow(java.awt.Point p)
	{
		if (!active || currentGuide == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return 0;
		}
		HcimGuideConfig.ArrowMode mode = config.navArrows();
		if (mode == HcimGuideConfig.ArrowMode.ATTACHED)
		{
			return hudOverlay.hitArrow(p);
		}
		if (mode == HcimGuideConfig.ArrowMode.FLOATING)
		{
			return stepNavOverlay.hitArrow(p);
		}
		return 0;
	}

	/**
	 * Keep the HUD's current-step item pictures in sync: when the first
	 * unchecked step of the active bank changes, resolve its items' ids on the
	 * executor (never on the client thread) and publish an immutable snapshot
	 * for the overlay. Runs every game tick; the common case is one cached
	 * bank lookup and a key comparison.
	 */
	private void updateHudItems()
	{
		if (!config.showHudOverlay() || !config.hudShowStepItems())
		{
			clearHudItems();
			return;
		}
		Guide guide = currentGuide;
		GuideBank bank = guide == null ? null : findActiveBank(guide);
		GuideStep first = null;
		if (bank != null)
		{
			for (GuideStep step : bank.getSteps())
			{
				if (!isStepDone(step.getKey()))
				{
					first = step;
					break;
				}
			}
		}
		if (first == null)
		{
			clearHudItems();
			return;
		}
		if (first.getKey().equals(hudItemsKey))
		{
			return;
		}
		hudItemsKey = first.getKey();
		final List<ItemReq> reqs = stepItems.get(first.getKey());
		if (reqs == null || reqs.isEmpty())
		{
			// bump the generation too: an in-flight resolve for the PREVIOUS
			// step must not republish its icons onto this item-less step
			hudItemsGen.incrementAndGet();
			hudItems = null;
			return;
		}
		final int gen = hudItemsGen.incrementAndGet();
		hudItems = null; // no stale icons while the new step resolves
		executor.execute(() ->
		{
			if (!active)
			{
				return;
			}
			int[] ids = iconResolver.resolve(reqs);
			if (hudItemsGen.get() == gen)
			{
				hudItems = new HudItems(reqs, ids);
			}
		});
	}

	/** Clear the HUD item snapshot AND invalidate any in-flight resolve. */
	private void clearHudItems()
	{
		hudItemsGen.incrementAndGet();
		hudItems = null;
		hudItemsKey = null;
	}

	/** HUD accessor: true when the current section's items are all carried. */
	boolean isTripReady()
	{
		return tripReadyVisible;
	}

	/**
	 * "Trip ready": every item requirement of the current section's remaining
	 * steps is in the inventory. Only requirements that resolve to a real item
	 * are counted - free-text entries ("2 Food", "Combat Gear") can never be
	 * verified and must not hold the indicator hostage. Sound fires once per
	 * section, on the moment readiness is reached. Client thread, every tick;
	 * the walk is over the CACHED active bank and bounded like the bank tag.
	 */
	private void updateTripReady()
	{
		boolean wantText = config.tripReadyIndicator();
		boolean wantSound = config.tripReadySound();
		Guide guide = currentGuide;
		GuideBank bank = (wantText || wantSound) && guide != null ? findActiveBank(guide) : null;
		if (bank == null || client.getGameState() != GameState.LOGGED_IN)
		{
			tripReady = false;
			tripReadyVisible = false;
			return;
		}
		boolean any = false;
		boolean all = true;
		int itemSteps = 0;
		outer:
		for (GuideStep step : bank.getSteps())
		{
			if (isStepDone(step.getKey()))
			{
				continue;
			}
			List<ItemReq> reqs = stepItems.get(step.getKey());
			if (reqs == null || reqs.isEmpty())
			{
				continue;
			}
			for (ItemReq req : reqs)
			{
				if (!iconResolver.isKnownItem(req.getName()))
				{
					continue; // unverifiable free text - ignore
				}
				any = true;
				if (inventory.countOf(req) < req.getQuantity())
				{
					all = false;
					break outer;
				}
			}
			if (++itemSteps >= 12)
			{
				break; // same bound as the bank tag: a trip is one inventory
			}
		}
		boolean ready = any && all;
		// baseline tick for a newly active bank: item-name resolution may
		// still be in flight, so a same-tick "ready" verdict is not trusted
		// with the sound - the TEXT self-corrects, the ding cannot
		boolean baseline = !bank.getId().equals(tripReadyEvalBank);
		tripReadyEvalBank = bank.getId();
		if (ready && !tripReady && !baseline && wantSound
			&& !bank.getId().equals(tripReadySoundedBank))
		{
			tripReadySoundedBank = bank.getId();
			try
			{
				client.playSoundEffect(TRIP_READY_SOUND);
			}
			catch (Exception ignored)
			{
				// sound is best-effort; never let it break the tick
			}
		}
		tripReady = ready;
		tripReadyVisible = ready && wantText;
	}

	/**
	 * Chat message + optional sound the moment a bank section is finished:
	 * fires when the active bank MOVES FORWARD past a section whose steps are
	 * all done. Moving backward (untick, clear-after, undo) never notifies.
	 * Client thread, every tick; normally a single string comparison.
	 */
	private void updateSectionCompleteNotice()
	{
		Guide guide = currentGuide;
		GuideBank cur = guide == null ? null : findActiveBank(guide);
		String curId = cur == null ? null : cur.getId();
		if (guide != prevActiveBankGuide || sectionNoticeResetPending)
		{
			// new guide model or wholesale progress swap: re-baseline, never
			// "notice" the jump itself
			sectionNoticeResetPending = false;
			prevActiveBankGuide = guide;
			prevActiveBankId = curId;
			tripReadySoundedBank = null;
			return;
		}
		if (java.util.Objects.equals(curId, prevActiveBankId))
		{
			return;
		}
		String finishedId = prevActiveBankId;
		prevActiveBankId = curId;
		if (finishedId == null
			|| (!config.bankCompleteMessage() && !config.bankCompleteSound())
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		GuideBank finished = null;
		outer:
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				if (bank.getId().equals(finishedId))
				{
					finished = bank;
					break outer;
				}
			}
		}
		if (finished == null || finished.getSteps().isEmpty())
		{
			return;
		}
		for (GuideStep step : finished.getSteps())
		{
			if (!isStepDone(step.getKey()))
			{
				return; // moved backward or sideways - not a completion
			}
		}
		if (config.bankCompleteMessage())
		{
			// removeTags: guide titles must never smuggle chat tags
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Guide Overlay: " + Text.removeTags(finished.getTitle()) + " complete!", null);
		}
		if (config.bankCompleteSound())
		{
			try
			{
				client.playSoundEffect(SUCCESS_SOUND);
			}
			catch (Exception ignored)
			{
				// best-effort
			}
		}
	}

	/**
	 * A tiny free-floating overlay with no position yet renders in a default
	 * corner where it's easy to lose. The first time Floating mode is active
	 * (per selection), place the arrows in the middle of the screen so
	 * they're immediately visible and ready to Alt+drag wherever the player
	 * wants. A position the player already chose is never overridden.
	 * Client thread (game tick); cheap one-flag check after the first pass.
	 */
	private void ensureFloatingArrowsFindable()
	{
		if (navCenterChecked || config.navArrows() != HcimGuideConfig.ArrowMode.FLOATING)
		{
			return;
		}
		if (stepNavOverlay.getPreferredLocation() != null
			|| stepNavOverlay.getPreferredPosition() != null)
		{
			navCenterChecked = true; // player has placed them - respect that
			return;
		}
		int w = client.getCanvasWidth();
		int h = client.getCanvasHeight();
		if (w <= 0 || h <= 0)
		{
			return; // canvas not ready - retry next tick
		}
		navCenterChecked = true;
		int arrowsW = StepNavOverlay.BUTTON_W * 2 + StepNavOverlay.BUTTON_GAP;
		stepNavOverlay.setPreferredLocation(new java.awt.Point(
			Math.max(0, (w - arrowsW) / 2),
			Math.max(0, h / 2 - StepNavOverlay.BUTTON_H / 2)));
		overlayManager.saveOverlay(stepNavOverlay);
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
			List<ItemReq> reqs = grid.getItems();
			int[] ids = iconResolver.resolve(reqs);
			grid.applyIcons(itemManager, reqs, ids);
			// names the price search can't see (untradeables like talismans
			// and quest amulets) get one chunked full-database scan; when it
			// finds anything, every visible grid re-resolves
			if (iconResolver.hasPendingScan())
			{
				iconResolver.scanFullDatabase(() ->
					SwingUtilities.invokeLater(() -> panel.reresolveIcons()));
			}
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
		stepHighlightsDirty = true;
		persistCompletedSteps();
		if (completed)
		{
			releasePinForStep(stepKey);
			// when this very completion finishes the ACTIVE section, only the
			// section-complete chime should play - not the same sound twice.
			// Backfilling the last step of some other bank never triggers the
			// section notice, so it keeps the step chime.
			int hash = stepKey.indexOf('#');
			String bankId = hash > 0 ? stepKey.substring(0, hash) : null;
			boolean sectionChimes = config.bankCompleteSound()
				&& bankId != null && bankId.equals(prevActiveBankId)
				&& bankNowDone(stepKey);
			if (!sectionChimes)
			{
				playStepCompleteSound();
			}
		}
	}

	/** Whether every step of the bank owning this key is now done. */
	private boolean bankNowDone(String stepKey)
	{
		Guide guide = currentGuide;
		int hash = stepKey == null ? -1 : stepKey.indexOf('#');
		if (guide == null || hash <= 0)
		{
			return false;
		}
		String bankId = stepKey.substring(0, hash);
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				if (!bank.getId().equals(bankId))
				{
					continue;
				}
				for (GuideStep s : bank.getSteps())
				{
					if (!isStepDone(s.getKey()))
					{
						return false;
					}
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Success chime for a single step getting checked off (manually or by
	 * auto-completion). Safe from any thread; the effect itself must play on
	 * the client thread. Bulk administrative actions (mark-bank-complete,
	 * account sync, undo) stay silent on purpose.
	 */
	private void playStepCompleteSound()
	{
		if (!active || !config.stepCompleteSound())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				client.playSoundEffect(SUCCESS_SOUND);
			}
			catch (Exception e)
			{
				// sound is best-effort; never let it break anything
			}
		});
	}

	/** Bulk update used by "mark bank complete" etc. Persists once. */
	void setCompletedBulk(Iterable<GuideStep> steps, boolean completed)
	{
		String pinnedChanged = null;
		synchronized (completedSteps)
		{
			for (GuideStep s : steps)
			{
				if (completed)
				{
					completedSteps.add(s.getKey());
					if (s.getKey().equals(pinnedStepKey))
					{
						pinnedChanged = s.getKey();
					}
				}
				else
				{
					completedSteps.remove(s.getKey());
					autoSuppressed.add(s.getKey());
				}
			}
		}
		activeBankDirty = true;
		stepHighlightsDirty = true;
		persistCompletedSteps();
		releasePinForStep(pinnedChanged);
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

	// ------------------------------------------------------------------ skipped steps

	boolean isSkipped(String stepKey)
	{
		synchronized (skippedSteps)
		{
			return skippedSteps.contains(stepKey);
		}
	}

	/** Completed OR skipped: the step no longer needs doing. */
	boolean isStepDone(String stepKey)
	{
		return isCompleted(stepKey) || isSkipped(stepKey);
	}

	void setSkipped(String stepKey, boolean skipped)
	{
		synchronized (skippedSteps)
		{
			if (skipped)
			{
				skippedSteps.add(stepKey);
			}
			else
			{
				skippedSteps.remove(stepKey);
			}
		}
		if (skipped)
		{
			// a skipped step must never auto-complete out from under the player
			autoSuppressed.add(stepKey);
		}
		activeBankDirty = true;
		stepHighlightsDirty = true;
		persistSkippedSteps();
		if (skipped)
		{
			releasePinForStep(stepKey);
		}
	}

	/**
	 * Header/HUD progress that treats skipped steps as not-required:
	 * {completed-and-not-skipped, total-not-skipped}.
	 */
	int[] progressOf(Iterable<GuideStep> steps)
	{
		Set<String> skip;
		synchronized (skippedSteps)
		{
			skip = skippedSteps.isEmpty() ? Collections.emptySet() : new HashSet<>(skippedSteps);
		}
		int done = 0;
		int total = 0;
		synchronized (completedSteps)
		{
			for (GuideStep s : steps)
			{
				if (skip.contains(s.getKey()))
				{
					continue;
				}
				total++;
				if (completedSteps.contains(s.getKey()))
				{
					done++;
				}
			}
		}
		return new int[]{done, total};
	}

	private String skippedKey()
	{
		return "skippedSteps." + currentGuideId;
	}

	private void loadSkippedSteps()
	{
		String key = skippedKey();
		String json = null;
		if (config.perCharacterProgress())
		{
			json = configManager.getRSProfileConfiguration(HcimGuideConfig.GROUP, key);
		}
		if (json == null)
		{
			json = configManager.getConfiguration(HcimGuideConfig.GROUP, key);
		}
		synchronized (skippedSteps)
		{
			skippedSteps.clear();
			if (json != null && !json.isEmpty())
			{
				try
				{
					Set<String> saved = gson.fromJson(json, STRING_SET);
					if (saved != null)
					{
						skippedSteps.addAll(saved);
					}
				}
				catch (Exception e)
				{
					log.warn("Could not parse saved skipped steps", e);
				}
			}
		}
		activeBankDirty = true;
		stepHighlightsDirty = true;
	}

	private void persistSkippedSteps()
	{
		// snapshot and write under the lock, same reasoning as
		// persistCompletedSteps; same per-character routing as progress
		synchronized (skippedSteps)
		{
			String json = gson.toJson(skippedSteps);
			if (config.perCharacterProgress() && configManager.getRSProfileKey() != null)
			{
				configManager.setRSProfileConfiguration(HcimGuideConfig.GROUP, skippedKey(), json);
			}
			else
			{
				configManager.setConfiguration(HcimGuideConfig.GROUP, skippedKey(), json);
			}
		}
	}

	// ------------------------------------------------------------------ bulk undo

	private final Object undoLock = new Object();
	private Set<String> undoCompleted;
	private Set<String> undoSkipped;
	private String undoGuideId;
	private String undoLabel;

	/**
	 * Remember the full progress state so the NEXT bulk action can be undone.
	 * Called immediately before every bulk mutation (catch-up, clear-after,
	 * bank bulk complete/incomplete, account sync, progress import).
	 */
	void snapshotBeforeBulk(String label)
	{
		Set<String> completed;
		Set<String> skipped;
		synchronized (completedSteps)
		{
			completed = new HashSet<>(completedSteps);
		}
		synchronized (skippedSteps)
		{
			skipped = new HashSet<>(skippedSteps);
		}
		synchronized (undoLock)
		{
			undoCompleted = completed;
			undoSkipped = skipped;
			undoGuideId = currentGuideId;
			undoLabel = label;
		}
	}

	/**
	 * Restore the snapshot taken before the last bulk action (one level).
	 *
	 * @return true when a snapshot was restored
	 */
	boolean undoLastBulk()
	{
		Set<String> completed;
		Set<String> skipped;
		String label;
		synchronized (undoLock)
		{
			if (undoCompleted == null || !currentGuideId.equals(undoGuideId))
			{
				SwingUtilities.invokeLater(() -> panel.setStatus("Nothing to undo"));
				return false;
			}
			completed = undoCompleted;
			skipped = undoSkipped;
			label = undoLabel;
			undoCompleted = null;
			undoSkipped = null;
			undoLabel = null;
		}
		Set<String> unticked;
		synchronized (completedSteps)
		{
			// steps the undo is about to UNTICK must not instantly re-auto-
			// complete - the undo is an explicit manual decision and wins
			unticked = new HashSet<>(completedSteps);
			unticked.removeAll(completed);
			completedSteps.clear();
			completedSteps.addAll(completed);
		}
		autoSuppressed.addAll(unticked);
		synchronized (skippedSteps)
		{
			skippedSteps.clear();
			skippedSteps.addAll(skipped);
		}
		persistCompletedSteps();
		persistSkippedSteps();
		activeBankDirty = true;
		stepHighlightsDirty = true;
		sectionNoticeResetPending = true;
		final String doneLabel = label;
		SwingUtilities.invokeLater(() ->
		{
			if (active && panel != null)
			{
				panel.refreshFromModel();
				panel.setStatus("Undid: " + doneLabel);
			}
		});
		return true;
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
		sectionNoticeResetPending = true; // store swap, not gameplay progress
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
		stepHighlightsDirty = true;
	}

	private void persistCompletedSteps()
	{
		// snapshot AND write under the lock: three threads persist (EDT
		// checkbox, client-thread auto-completion, executor migration), and
		// writing outside the lock could land an older snapshot after a newer
		// one. The config write is quick and nothing else nests locks with
		// completedSteps, so holding it here is safe.
		synchronized (completedSteps)
		{
			writeProgressJson(gson.toJson(completedSteps));
		}
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
		// same one-time per-character seeding for skipped steps
		String skey = skippedKey();
		if (config.perCharacterProgress() && configManager.getRSProfileKey() != null
			&& configManager.getRSProfileConfiguration(HcimGuideConfig.GROUP, skey) == null)
		{
			String sharedSkip = configManager.getConfiguration(HcimGuideConfig.GROUP, skey);
			if (sharedSkip != null && !sharedSkip.isEmpty())
			{
				configManager.setRSProfileConfiguration(HcimGuideConfig.GROUP, skey, sharedSkip);
			}
		}
		loadCompletedSteps();
		loadSkippedSteps();
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
			stepHighlightsDirty = true;
		}
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		if (groundItems.remove(event.getItem()) != null)
		{
			stepHighlightsDirty = true;
		}
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
		stepHighlightsDirty = true;
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
		stepHighlightsDirty = true;
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

	// ------------------------------------------------------------------ scene objects

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		trackObject(event.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		if (sceneObjects.remove(event.getGameObject()) != null)
		{
			stepHighlightsDirty = true;
		}
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		trackObject(event.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		if (sceneObjects.remove(event.getWallObject()) != null)
		{
			stepHighlightsDirty = true;
		}
	}

	/**
	 * Remember whitelisted interactables (ladders, altars, doors, ...).
	 * Client thread. Tracks UNCONDITIONALLY (not config-gated): spawn events
	 * only fire on scene load, so gating here would leave the feature blank
	 * until the next region change after the user toggles it on. The
	 * whitelist keeps the map to a handful of objects either way.
	 */
	private void trackObject(net.runelite.api.TileObject obj)
	{
		if (obj == null)
		{
			return;
		}
		String norm = whitelistedObjectName(obj.getId());
		if (norm != null)
		{
			sceneObjects.put(obj, norm);
			stepHighlightsDirty = true;
		}
	}

	/**
	 * Normalized name when the object is on the whitelist, else null.
	 * Plain objects cache their verdict by id; varbit multilocs (doors,
	 * altars with states) resolve their CURRENT impostor at spawn time and
	 * are never cached, since the name varies at runtime.
	 */
	private String whitelistedObjectName(int id)
	{
		String cached = objectNameCache.get(id);
		if (cached != null)
		{
			return cached.isEmpty() ? null : cached;
		}
		String norm = "";
		boolean cacheable = true;
		try
		{
			net.runelite.api.ObjectComposition c = client.getObjectDefinition(id);
			if (c != null && c.getImpostorIds() != null)
			{
				cacheable = false;
				c = c.getImpostor();
			}
			String name = c == null ? null : c.getName();
			if (name != null && !"null".equals(name))
			{
				String n = Names.normalize(Text.removeTags(name));
				if (TargetExtractor.OBJECT_WORDS.contains(n))
				{
					norm = n;
				}
			}
		}
		catch (Exception ignored)
		{
			// unreadable definition/impostor -> treat as not whitelisted
		}
		if (cacheable)
		{
			objectNameCache.put(id, norm);
		}
		return norm.isEmpty() ? null : norm;
	}

	List<net.runelite.api.TileObject> getObjectHighlights()
	{
		return objectHighlights;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING || event.getGameState() == GameState.LOGIN_SCREEN)
		{
			groundItems.clear();
			groundHighlights = new ArrayList<>();
			stepNpcs = new ArrayList<>();
			sceneObjects.clear();
			objectHighlights = new ArrayList<>();
			stepHighlightsDirty = true;
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
			nextStepPoint = null; // no stale compass fallback across sessions
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
			|| key.startsWith("skippedSteps")
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
			|| "autoCollapseCompleted".equals(key) || "panelTextSize".equals(key)
			|| "itemPresenceBorders".equals(key))
		{
			SwingUtilities.invokeLater(() -> panel.onConfigChanged());
		}

		if ("highlightStepNpcs".equals(key) || "highlightGroundItems".equals(key))
		{
			stepHighlightsDirty = true;
		}

		// switching progress scope re-routes reads to the other store
		if ("perCharacterProgress".equals(key))
		{
			loadCompletedSteps();
			loadSkippedSteps();
			autoSuppressed.clear();
			SwingUtilities.invokeLater(() -> panel.refreshFromModel());
		}

		// take the world map marker down immediately when its toggle turns off
		if ("showWorldMapMarker".equals(key) && !config.showWorldMapMarker())
		{
			clientThread.invokeLater(this::removeMapMarker);
		}

		// layout preferences changed -> rebuild the tag (and its layout) next cycle
		if ("bankTagStepOrder".equals(key) || "bankTagUseLayout".equals(key))
		{
			bankTagIntegration.invalidate();
			// turning the managed arrangement OFF hands the tab back to the
			// user - remove the generated layout so stale entries don't linger
			if ("bankTagStepOrder".equals(key) && !config.bankTagStepOrder())
			{
				bankTagIntegration.clearManagedLayout();
			}
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

		// re-run the floating-arrows placement check when the mode changes
		if ("navArrows".equals(key))
		{
			navCenterChecked = false;
		}
	}

	// ------------------------------------------------------------------ auto-completion

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		++tickCounter;
		updateTargetTracking();
		updateStepHighlights(tickCounter % HIGHLIGHT_SAFETY_REFRESH_TICKS == 0);
		// every tick so the drawn path reacts to a completed step within one
		// game tick - it's a few cached-field reads and a deduped message
		updatePathHandoff();
		// cheap (cached bank + key compare); resolves off-thread on step change
		updateHudItems();
		// one-shot: floating arrows that were never positioned start mid-screen
		ensureFloatingArrowsFindable();
		// trip-ready state + section-complete confirmation (both cheap walks
		// over the cached active bank; sounds/messages only on transitions)
		updateTripReady();
		updateSectionCompleteNotice();
		// dialogue-choice guidance: which option to outline right now
		updateDialogHighlight();

		if (tickCounter % EVAL_INTERVAL_TICKS == 0)
		{
			evaluateAutoCompletion();
			syncBankTag();
			updateRouting();
			prefetchUpcomingIcons();
		}
	}

	/**
	 * Keep the Shortest Path plugin (if installed) pointed at the CURRENT
	 * objective. Runs every tick: the objective comes from fields already
	 * maintained by updateTargetTracking, and PathfinderIntegration dedups,
	 * so the common case is a couple of comparisons and no message at all.
	 */
	private void updatePathHandoff()
	{
		if (!config.routeUseShortestPath() || currentGuide == null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			pathfinder.clear();
			return;
		}
		WorldPoint objective = currentObjective();
		if (objective == null)
		{
			pathfinder.clear();
			return;
		}
		pathfinder.setTarget(objective, targetNpc != null);
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
		// the Shortest Path hand-off runs per-tick in updatePathHandoff();
		// this method only maintains the HUD suggestion text
		if (!config.routeSuggestions() || currentGuide == null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			routeSuggestion = null;
			return;
		}
		WorldPoint objective = currentObjective();
		if (objective == null)
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
					List<ItemReq> stepReqs = stepItems.get(step.getKey());
					if (stepReqs != null)
					{
						reqs.addAll(stepReqs);
					}
				}
				if (--remaining <= 0 || reqs.size() > 200)
				{
					break outer; // bounded background work even for huge sections
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
		// A bank trip is at most 28 inventory slots: tagging beyond the next
		// handful of item-steps is noise. The cap also bounds this walk (and
		// the tag size) for guides that parse into one huge section.
		final int maxItemSteps = 12;
		int itemSteps = 0;
		List<ItemReq> items = new ArrayList<>();
		StringBuilder signature = new StringBuilder(active.getId());
		for (GuideStep step : active.getSteps())
		{
			if (isStepDone(step.getKey()))
			{
				continue;
			}
			List<ItemReq> stepReqs = stepItems.get(step.getKey());
			if (stepReqs != null && !stepReqs.isEmpty())
			{
				items.addAll(stepReqs);
				signature.append('|').append(step.getKey());
				if (++itemSteps >= maxItemSteps)
				{
					break;
				}
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
		if (event.getGroupId() == InterfaceID.CHATMENU)
		{
			lastDialogActivityTick = tickCounter;
			// this menu belongs to the conversation the click started - the
			// stale-menu dismissal it guarded against can no longer happen
			pendingInteractionClick = false;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		// an option menu closing means the player answered it: the NEXT menu
		// of the conversation wants the NEXT number in the sequence. Only a
		// menu of the MATCHED conversation consumes a position (unrelated
		// conversations must never desync the sequence), and a menu that a
		// fresh interaction click swept away was dismissed, not answered
		if (event.getGroupId() == InterfaceID.CHATMENU)
		{
			lastDialogActivityTick = tickCounter;
			if (pendingInteractionClick)
			{
				pendingInteractionClick = false;
			}
			else if (dialogAdvanceArmed && dialogSeq != null && dialogPos < dialogSeq.length)
			{
				dialogPos++;
			}
		}
	}

	/**
	 * Clicking an NPC or a scene object starts a new conversation: remember
	 * who with, so the dialogue guidance can tell the guided step's
	 * conversation apart from small talk with bystanders. Reading the
	 * player's own click - display bookkeeping only, nothing is acted on.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		boolean npcClick = action == MenuAction.NPC_FIRST_OPTION
			|| action == MenuAction.NPC_SECOND_OPTION
			|| action == MenuAction.NPC_THIRD_OPTION
			|| action == MenuAction.NPC_FOURTH_OPTION
			|| action == MenuAction.NPC_FIFTH_OPTION;
		boolean objectClick = action == MenuAction.GAME_OBJECT_FIRST_OPTION
			|| action == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| action == MenuAction.GAME_OBJECT_THIRD_OPTION
			|| action == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION;
		if (!npcClick && !objectClick)
		{
			return;
		}
		// a fresh interaction ends whatever conversation came before it, and
		// its first option menu is menu #1 of the sequence again
		conversationNames.clear();
		dialogPos = 0;
		lastInteractionClickTick = tickCounter;
		pendingInteractionClick = true;
		String name = null;
		if (npcClick)
		{
			NPC npc = event.getMenuEntry().getNpc();
			if (npc != null && npc.getName() != null)
			{
				name = Text.removeTags(npc.getName());
			}
		}
		if (name == null && event.getMenuTarget() != null)
		{
			// object clicks and NPC entries without a live NPC: the menu
			// target text is the name (minus tags and a combat-level suffix)
			name = LEVEL_SUFFIX.matcher(Text.removeTags(event.getMenuTarget())).replaceAll("");
		}
		String norm = Names.normalize(name);
		if (!norm.isEmpty())
		{
			conversationNames.add(norm);
		}
	}

	/** Overlay accessor: 1-based option to outline now, or -1. */
	int getDialogHighlightOption()
	{
		return dialogHighlightOption;
	}

	/**
	 * Maintain the dialogue-guidance state. The guiding step is the pinned
	 * step when set, else the current (first not-done) step of the active
	 * bank. Position resets when the step changes, a new interaction starts,
	 * or the conversation goes quiet. Client thread, every tick.
	 */
	private void updateDialogHighlight()
	{
		// conversation bookkeeping runs even with the highlight toggled off
		// (and with no guide), so the position and partner names track the
		// live conversation and re-enabling mid-chat resumes correctly.
		// While any dialogue widget is on screen the conversation is alive -
		// a slow read must NEVER reset the sequence position (that would
		// point the highlight at the wrong option). Only a few quiet ticks
		// with no dialogue at all mean the conversation ended or was
		// abandoned - and the names a click seeded survive the walk to the
		// target through their own longer grace period.
		if (isDialogueOpen())
		{
			lastDialogActivityTick = tickCounter;
			// a click that failed to dismiss this dialogue (locked/scripted)
			// will never produce the WidgetClosed the flag waits for - clear
			// it so it can't swallow a real answer's close later
			if (pendingInteractionClick && lastInteractionClickTick >= 0
				&& tickCounter - lastInteractionClickTick > DIALOG_RESET_TICKS)
			{
				pendingInteractionClick = false;
			}
			captureConversationSpeaker();
		}
		else if (lastDialogActivityTick >= 0
			&& tickCounter - lastDialogActivityTick > DIALOG_RESET_TICKS)
		{
			dialogPos = 0;
			if (!conversationNames.isEmpty()
				&& (lastInteractionClickTick < 0
					|| tickCounter - lastInteractionClickTick > CLICK_SEED_GRACE_TICKS))
			{
				conversationNames.clear();
			}
		}
		if (currentGuide == null)
		{
			dialogStepKey = null;
			dialogSeq = null;
			dialogAdvanceArmed = false;
			dialogHighlightOption = -1;
			return;
		}
		// pinned step wins - that's the one being worked on
		String key = pinnedStepKey;
		if (key == null)
		{
			Guide guide = currentGuide;
			GuideBank bank = guide == null ? null : findActiveBank(guide);
			if (bank != null)
			{
				for (GuideStep step : bank.getSteps())
				{
					if (!isStepDone(step.getKey()))
					{
						key = step.getKey();
						break;
					}
				}
			}
		}
		if (key == null)
		{
			dialogStepKey = null;
			dialogSeq = null;
			dialogAdvanceArmed = false;
			dialogHighlightOption = -1;
			return;
		}
		if (!key.equals(dialogStepKey))
		{
			dialogStepKey = key;
			dialogSeq = dialogSequences.get(key);
			dialogPos = 0;
		}
		if (dialogSeq == null)
		{
			dialogAdvanceArmed = false;
			dialogHighlightOption = -1;
			return;
		}
		// the sequence only applies to the conversation the step describes:
		// no name match, no highlight and no position advance. A step whose
		// NPC/object could not be extracted highlights nothing rather than
		// mislead (Quest Helper covers mid-quest dialogue).
		boolean matched = TargetExtractor.conversationMatches(
			conversationNames, dialogNpcs.get(key), dialogObjectWords.get(key));
		dialogAdvanceArmed = matched && dialogPos < dialogSeq.length;
		dialogHighlightOption = dialogAdvanceArmed && config.highlightDialogOptions()
			? dialogSeq[dialogPos] : -1;
	}

	/**
	 * While a dialogue is showing, note who is speaking (the name line of the
	 * NPC chat widget) so mid-conversation menus - and conversations the
	 * player didn't start with a click - still identify their NPC.
	 */
	private void captureConversationSpeaker()
	{
		if (conversationNames.size() >= MAX_CONVERSATION_NAMES)
		{
			return;
		}
		Widget nameWidget = client.getWidget(InterfaceID.ChatLeft.NAME);
		if (nameWidget == null || nameWidget.isHidden() || nameWidget.getText() == null)
		{
			return;
		}
		String norm = Names.normalize(Text.removeTags(nameWidget.getText()));
		if (!norm.isEmpty())
		{
			conversationNames.add(norm);
		}
	}

	/**
	 * Any dialogue widget open: options menu, NPC/player text, object box,
	 * double-item box, or plain message box - every widget a conversation can
	 * show between option menus. Missing one here would let the quiet-timeout
	 * reset the sequence MID-conversation and highlight the wrong option.
	 */
	private boolean isDialogueOpen()
	{
		return client.getWidget(InterfaceID.CHATMENU, 1) != null
			|| client.getWidget(InterfaceID.CHAT_LEFT, 0) != null
			|| client.getWidget(InterfaceID.CHAT_RIGHT, 0) != null
			|| client.getWidget(InterfaceID.OBJECTBOX, 0) != null
			|| client.getWidget(InterfaceID.OBJECTBOX_DOUBLE, 0) != null
			|| client.getWidget(InterfaceID.MESSAGEBOX, 0) != null;
	}

	/**
	 * Recompute which NPCs and ground items the current bank's unchecked steps
	 * reference, so the overlay can highlight them. Client thread only.
	 */
	private void updateStepHighlights(boolean forceRefresh)
	{
		if (!stepHighlightsDirty && !forceRefresh)
		{
			return;
		}
		stepHighlightsDirty = false;

		Guide guide = currentGuide;
		boolean npcsWanted = config.highlightStepNpcs();
		boolean itemsWanted = config.highlightGroundItems();
		boolean objectsWanted = config.highlightStepObjects();
		if (guide == null || (!npcsWanted && !itemsWanted && !objectsWanted))
		{
			stepNpcs = new ArrayList<>();
			groundHighlights = new ArrayList<>();
			objectHighlights = new ArrayList<>();
			return;
		}

		// wanted names come from precomputed maps - no regex on step text here.
		// This runs only after relevant changes (plus a periodic safety refresh),
		// but still bound the walk: only the next unchecked
		// steps matter for what's on screen anyway, and the cap keeps a guide
		// that parses into one huge section from turning this into a 600-step
		// scan per tick.
		final int maxScanSteps = 40;
		int scanned = 0;
		Set<String> wantedNorm = new HashSet<>();
		Set<String> wantedObjects = new HashSet<>();
		List<ItemReq> itemReqs = new ArrayList<>();
		GuideBank active = findActiveBank(guide);
		if (active != null)
		{
			for (GuideStep step : active.getSteps())
			{
				if (isStepDone(step.getKey()))
				{
					continue;
				}
				if (++scanned > maxScanSteps)
				{
					break;
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
				if (objectsWanted)
				{
					wantedObjects.addAll(TargetExtractor.objectWordsIn(step.getText()));
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

		// whitelisted scene objects the remaining steps mention, capped so a
		// door-heavy building can't turn the overlay into a light show
		List<net.runelite.api.TileObject> objects = new ArrayList<>();
		if (!wantedObjects.isEmpty())
		{
			final int maxObjects = 40;
			for (Map.Entry<net.runelite.api.TileObject, String> e : sceneObjects.entrySet())
			{
				if (wantedObjects.contains(e.getValue()))
				{
					objects.add(e.getKey());
					if (objects.size() >= maxObjects)
					{
						break;
					}
				}
			}
		}
		objectHighlights = objects;
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
			if (isStepDone(step.getKey()) || autoSuppressed.contains(step.getKey()))
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
		// one chime per burst, not per step - and none when the burst just
		// finished the section (the section-complete chime covers it)
		String furthest = justCompleted.get(justCompleted.size() - 1).getKey();
		if (!(config.bankCompleteSound() && bankNowDone(furthest)))
		{
			playStepCompleteSound();
		}

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
					if (!isStepDone(step.getKey()))
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
					if (!isStepDone(step.getKey()) && stepTargets.containsKey(step.getKey()))
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

	/**
	 * The step that JUST became completed/skipped can no longer be the tracked
	 * target: without this, its NPC keeps the outline and hint arrow forever
	 * (e.g. the candle seller after "buy a candle" is ticked). Advances the
	 * pin to the next trackable step AFTER it in guide order when auto-advance
	 * is on, otherwise clears it.
	 *
	 * Strictly event-scoped: fires only when the changed step IS the pinned
	 * one, so completing/unticking/skipping unrelated steps never disturbs a
	 * pin - including a deliberately re-pinned already-done step. The state
	 * change runs on the client thread (immediately when already there),
	 * serializing with evaluateAutoCompletion's advance and target tracking;
	 * the pin is re-validated there so a stale request self-cancels.
	 */
	private void releasePinForStep(String changedKey)
	{
		if (changedKey == null || !changedKey.equals(pinnedStepKey))
		{
			return;
		}
		clientThread.invoke(() ->
		{
			String pinned = pinnedStepKey;
			if (pinned == null || !pinned.equals(changedKey) || !isStepDone(pinned))
			{
				return;
			}
			Guide guide = currentGuide;
			GuideStep next = (guide != null && config.autoTrackNext())
				? findNextTrackableStepAfter(guide, pinned) : null;
			if (next != null)
			{
				pinnedStepKey = next.getKey();
				targetName = stepTargets.get(next.getKey());
				final String newTarget = targetName;
				SwingUtilities.invokeLater(() -> panel.onPinChanged(newTarget));
			}
			else
			{
				pinStep(null);
				SwingUtilities.invokeLater(() -> panel.onPinChanged(null));
			}
		});
	}

	/**
	 * First unchecked trackable step strictly AFTER the given step in guide
	 * order - the pin must advance forward from where the player is working,
	 * never jump back to stale steps left unchecked in earlier banks.
	 */
	private GuideStep findNextTrackableStepAfter(Guide guide, String afterKey)
	{
		boolean seen = false;
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					if (!seen)
					{
						seen = step.getKey().equals(afterKey);
						continue;
					}
					if (!isStepDone(step.getKey()) && stepTargets.containsKey(step.getKey()))
					{
						return step;
					}
				}
			}
		}
		return null;
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

	/** The next unchecked step's known target location (or null). Client thread. */
	WorldPoint getNextStepPoint()
	{
		return nextStepPoint;
	}

	/**
	 * True while a pinned step is being tracked. The compass only falls back
	 * to the next-step point when NOTHING is pinned: while pinned,
	 * nextStepPoint is not refreshed and must not be pointed at.
	 */
	boolean hasPinnedTarget()
	{
		return targetName != null;
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
		// the next step's location feeds the world map marker, the routing
		// objective, AND the unpinned compass - computed whenever any wants it
		boolean routingWanted = config.routeSuggestions() || config.routeUseShortestPath();
		boolean compassWanted = config.showDirectionArrow() && config.compassNextStep();
		if ((!config.showNextStepOnMap() && !routingWanted && !compassWanted) || currentGuide == null)
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
				if (isStepDone(step.getKey()))
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

	/** Idempotent: same point and label -> no churn; changed label replaces it. */
	private void setMarker(WorldPoint point, String name)
	{
		if (point != null && point.equals(markerPoint) && mapMarker != null
			&& (name == null ? markerName == null : name.equals(markerName)))
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
		markerName = name;
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
		markerName = null;
	}
}
