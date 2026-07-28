package com.hcimguide;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
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
	/** Bounds manually edited/corrupted config before Gson allocates collections. */
	private static final int MAX_STORED_STATE_CHARS = 8 * 1024 * 1024;
	private static final int MAX_STORED_STATE_ENTRIES = 50_000;
	private static final int MAX_STORED_STATE_KEY_CHARS = 512;

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

	/**
	 * All keyboard and mouse handling lives in {@link GuideInputHandler}, which
	 * is the only class in the plugin that touches RuneLite's input API.
	 */
	@Inject
	private GuideInputHandler inputHandler;

	/**
	 * Clipboard and browser access, isolated so this class holds no desktop
	 * capability of its own.
	 */
	@Inject
	private GuideExternalActions externalActions;

	/** Plugin-generated chimes, for players who mute the game. */
	@Inject
	private ChimePlayer chimePlayer;

	/** The narrow set of actions the input handler may trigger. */
	private final GuideInputHandler.Actions inputActions = new GuideInputHandler.Actions()
	{
		@Override
		public void navigateStep(boolean forward)
		{
			HcimGuidePlugin.this.navigateStep(forward);
		}

		@Override
		public void toggleLocationGuide()
		{
			toggleLocationGuideForCurrentStep();
		}

		@Override
		public int hitNavArrow(java.awt.Point p)
		{
			return HcimGuidePlugin.this.hitNavArrow(p);
		}

		@Override
		public boolean hitLocationToggle(java.awt.Point p)
		{
			return HcimGuidePlugin.this.hitLocationToggle(p);
		}
	};

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
	private PlaceDirectory placeDirectory;

	@Inject
	private TransportResolver transportResolver;

	@Inject
	private CustomLocationStore customLocationStore;

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
	private PluginManager pluginManager;

	@Inject
	private DialogOptionOverlay dialogOptionOverlay;

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

	/**
	 * Which activation of this plugin is current. RuneLite reuses the instance
	 * across disable and re-enable, so {@link #active} alone cannot tell a
	 * callback from a previous activation apart from a live one.
	 */
	private final LifecycleGeneration lifecycle = new LifecycleGeneration();
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
	/** Step key -&gt; parsed guide step, for rare arrival-completion decisions. */
	private volatile Map<String, GuideStep> stepsByKey = Collections.emptyMap();
	/** Structural parent rows whose following row is more deeply nested. */
	private volatile Set<String> structuralParentSteps = Collections.emptySet();
	/**
	 * Step key -&gt; destination point. Direct entries come from a named place or
	 * known NPC/item location; inferred entries carry the last reliable area
	 * forward for consecutive local actions.
	 */
	private volatile Map<String, StepLocationPlan> stepLocationPlans = Collections.emptyMap();
	/** Runtime-only virtual phases; original GuideBank step lists never change. */
	private volatile Map<String, StepExecutionPlan> stepExecutionPlans = Collections.emptyMap();
	/** Parent step key -> semantic phase id; profile-scoped and resilient to reordering. */
	private final Map<String, String> activePhaseIds = new java.util.concurrent.ConcurrentHashMap<>();
	/**
	 * The planner's AUTOMATIC output, before custom-pin overrides. Kept so a
	 * pin mutation can restore or overlay a single step without re-running
	 * the whole planner (~1s on a 2,300-step guide) on the client thread.
	 */
	private volatile Map<String, StepLocationPlan> baseLocationPlans = Collections.emptyMap();
	/** Versioned cache metadata; prevents reparsing a loaded guide until a real input changes. */
	private volatile Guide locationPlanCacheGuide;
	private volatile String locationPlanCacheGuideId;
	private volatile long locationPlanCacheCustomRevision = -1;
	private volatile int locationPlanCacheLocationRevision = -1;
	private volatile int locationPlanCacheResolverVersion = -1;
	/** Active waypoint index per step; persisted through CustomLocationStore when enabled. */
	private final Map<String, Integer> activeWaypointIndexes = new java.util.concurrent.ConcurrentHashMap<>();
	/** Normalized target names of the current guide - bounds what the location store learns. */
	private volatile Set<String> targetNamesNorm = Collections.emptySet();
	private volatile InventorySnapshot inventory = InventorySnapshot.EMPTY;

	// far-target pointing (client thread only)
	private WorldPoint farTarget;
	private WorldMapPoint mapMarker;

	/**
	 * Last seen inventory and equipment contents.
	 *
	 * <p>Presence checks used to read the inventory only, so a requirement
	 * satisfied by something WORN - a Dramen staff, an Ardougne cloak, a ring of
	 * dueling - never counted and its step never ticked off. Both containers are
	 * kept so the snapshot can be rebuilt from the union whenever either
	 * changes.</p>
	 */
	private Item[] lastInventoryItems = new Item[0];
	private Item[] lastWornItems = new Item[0];
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
	/**
	 * Steps rewound with the Previous-step arrow whose CONDITION completion is
	 * dormant until the condition is next observed unmet. Arrival completion
	 * has a genuine edge (a fresh two-tick confirmed arrival) so a rewind
	 * re-arms it directly - but a quest-finished or level-reached condition is
	 * level-triggered and would otherwise snap the step straight back within
	 * one evaluation cycle, making the rewind arrow useless.
	 */
	private final Set<String> rewindArmPending = Collections.synchronizedSet(new HashSet<>());

	private int tickCounter;

	// pinned step target tracking
	private volatile String pinnedStepKey;

	/**
	 * Who placed the current pin. A USER pin is an explicit choice and rules
	 * guidance until the user unpins or navigates; an AUTOMATIC pin
	 * (auto-track-next) is only a fallback and must never outrank the current
	 * checklist step - that distinction is what lets backward navigation
	 * re-target the step under review instead of staying locked on a later
	 * automatically pinned one.
	 */
	private enum PinOrigin
	{
		NONE,
		AUTOMATIC,
		USER
	}

	private volatile PinOrigin pinOrigin = PinOrigin.NONE;
	private volatile String targetName;
	private volatile NPC targetNpc;
	private boolean hintArrowSet;
	/** Destination-aware suppression: carries across equivalent steps until the player leaves. */
	private final LocationSuppressionState locationSuppression = new LocationSuppressionState();
	/** Global five-minute display snooze; destination suppression is tracked separately. */
	private volatile int locationSnoozeUntilTick;
	private final WaypointArrivalTracker waypointArrivalTracker = new WaypointArrivalTracker();

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
	private String nextStepKey;                 // key owning nextStepPoint (suppression scope)
	private WorldPoint lastRoutePlayer;
	private WorldPoint lastRouteObjective;
	private long lastStockRevision = -1;
	private volatile boolean routeDirty = true; // config/stock changed -> recompute
	private enum GuidanceBand { NEAR, MEDIUM, FAR }
	private GuidanceBand guidanceBand = GuidanceBand.FAR;
	private volatile boolean questHelperActiveCached;
	private volatile int questHelperCheckedTick = Integer.MIN_VALUE;

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
	// COVERAGE NOTE: only GameObjects and WallObjects are tracked (the two
	// types guide objectives actually use); DecorativeObject/GroundObject have
	// no spawn/despawn subscriptions and are never inserted here. Anyone
	// adding a third type MUST add its despawn handler too, or this map leaks.
	private final Map<net.runelite.api.TileObject, String> sceneObjects = new HashMap<>();
	private List<net.runelite.api.TileObject> objectHighlights = new ArrayList<>();
	/**
	 * Object id -> normalized whitelisted name ("" = not whitelisted).
	 * Access-order LRU bounded against very long sessions crossing many
	 * regions (compositions are cheap to re-resolve on eviction).
	 * Client thread only.
	 */
	private final Map<Integer, String> objectNameCache =
		new LinkedHashMap<Integer, String>(64, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest)
		{
			return size() > 2048;
		}
	};

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

	@Provides
	HcimGuideConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HcimGuideConfig.class);
	}


	/**
	 * Run background work that must not outlive this activation. The generation
	 * is captured when the task is submitted, so a task completing after a
	 * disable, or after a disable and re-enable, is dropped instead of mutating
	 * a later session. Use the executor directly only for work that must still
	 * run during shutdown, such as flushing a pending save.
	 */
	/**
	 * Bind a network callback to the activation that STARTED the request.
	 *
	 * <p>The guarded dispatch helpers capture the generation when they are
	 * called. Inside a network callback that is after the download finished, so
	 * a response landing after a disable and re-enable would capture the NEW
	 * activation and be applied to it. Wrapping at submission time captures the
	 * right one.</p>
	 */
	private <T> java.util.function.Consumer<T> boundToActivation(
		java.util.function.Consumer<T> task)
	{
		final long generation = lifecycle.current();
		return value ->
		{
			if (lifecycle.isCurrent(generation))
			{
				task.accept(value);
			}
		};
	}

	/** Two-argument form of {@link #boundToActivation(java.util.function.Consumer)}. */
	private <A, B> java.util.function.BiConsumer<A, B> boundToActivation(
		java.util.function.BiConsumer<A, B> task)
	{
		final long generation = lifecycle.current();
		return (a, b) ->
		{
			if (lifecycle.isCurrent(generation))
			{
				task.accept(a, b);
			}
		};
	}

	private void runAsync(Runnable task)
	{
		final long generation = lifecycle.current();
		executor.execute(() ->
		{
			if (!lifecycle.isCurrent(generation))
			{
				return;
			}
			task.run();
		});
	}

	/**
	 * Client-thread work that must run even though the activation has already
	 * ended. Shutdown cleanup is the last act of the activation that is closing,
	 * not stale work from a previous one, so {@link #runOnClientThread}'s
	 * isCurrent check (which requires a LIVE activation) cannot be used. It is
	 * still not unconditional: shutDown runs on the EDT while this executes
	 * later on the game thread, so a fast disable/re-enable can start a new
	 * activation first - whose freshly populated state this cleanup would then
	 * wipe. The post-end token detects that: begin() advances the generation,
	 * so the cleanup yields and the new activation's own startup reset (which
	 * covers identical state) is the one that runs. Use this ONLY from shutDown,
	 * after lifecycle.end().
	 */
	private void runShutdownOnClientThread(Runnable task)
	{
		final long closing = lifecycle.current();
		clientThread.invokeLater(() ->
		{
			if (lifecycle.current() != closing)
			{
				return;
			}
			task.run();
		});
	}

	/**
	 * Swing UI work guarded by the same generation rule. Network callbacks queue
	 * status updates, and cancelling a call on shutdown fires onFailure, so
	 * without this a message such as "Download failed: Canceled" could appear in
	 * a later activation's panel.
	 */
	private void runOnSwing(Runnable task)
	{
		final long generation = lifecycle.current();
		SwingUtilities.invokeLater(() ->
		{
			if (!lifecycle.isCurrent(generation))
			{
				return;
			}
			task.run();
		});
	}

	/** Client-thread work guarded by the same generation rule as runAsync. */
	private void runOnClientThread(Runnable task)
	{
		final long generation = lifecycle.current();
		clientThread.invokeLater(() ->
		{
			if (!lifecycle.isCurrent(generation))
			{
				return;
			}
			task.run();
		});
	}

	@Override
	protected void startUp()
	{
		// One line at startup so a log makes the plugin's state visible. Without
		// it the plugin was completely silent, and a log could not distinguish
		// "running fine" from "never enabled".
		log.info("Guide Overlay starting: autoComplete={}, autoAdvanceWaypoints={}, "
			+ "arrowMode={}, soundSource={}", config.autoComplete(),
			config.autoAdvanceWaypoints(), config.navArrows(), config.soundSource());
		active = true;
		lifecycle.begin();
		// If this re-enable outran the previous activation's queued shutdown
		// cleanup, that cleanup yielded to us - so reset the shared
		// client-thread state under OUR generation instead. Runs within a
		// frame of startup, before any meaningful state accrues; idempotent
		// when the old cleanup did run.
		runOnClientThread(this::resetClientThreadState);
		loadedProgressProfileKey = config.perCharacterProgress()
			? configManager.getRSProfileKey() : null;
		profileProgressReady = !config.perCharacterProgress()
			|| loadedProgressProfileKey != null;
		profileReloadPending = config.perCharacterProgress()
			&& !profileProgressReady && client.getGameState() == GameState.LOGGED_IN;
		profileReloadAttempts = 0;
		locationSnoozeUntilTick = 0;
		migratePanelFontConfig();
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
		inputHandler.register(inputActions);

		// scrub a bank tag left behind by a crashed session if the feature is off
		// (when it's on, the first sync cycle rebuilds the tag anyway)
		if (!config.bankTagIntegration())
		{
			bankTagIntegration.cleanup();
		}

		// the REAL inventory stone background for item grids, from the
		// player's own game files (async; painted fallback shows until then)
		final long spriteGeneration = lifecycle.current();
		spriteManager.getSpriteAsync(net.runelite.api.SpriteID.FIXED_MODE_SIDE_PANEL_BACKGROUND, 0,
			img ->
			{
				if (!lifecycle.isCurrent(spriteGeneration))
				{
					return;
				}
				ItemGridPanel.setInventoryBackground(img);
				runOnSwing(() ->
				{
					if (panel != null)
					{
						panel.repaint();
					}
				});
			});

		// load the locally stored snapshot only - the plugin NEVER fetches on its own
		runAsync(() ->
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
			runOnSwing(() -> panel.setGuides(guideRegistry.list(), selectedFinal));
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

	/**
	 * One-time mapping of the pre-parity side-panel font keys onto the
	 * overlay-parity keys. Only fills a new key that has never been written,
	 * so an explicit choice made under the NEW settings is never overwritten;
	 * a legacy explicit choice carries over once and the legacy keys are then
	 * removed so only one set of side-panel font settings exists. Unset legacy
	 * keys migrate nothing and the new defaults (Sans serif / Plain / 10)
	 * apply.
	 */
	private void migratePanelFontConfig()
	{
		String group = HcimGuideConfig.GROUP;
		String legacyStyle = configManager.getConfiguration(group, "panelFontStyle");
		if (legacyStyle != null)
		{
			if (configManager.getConfiguration(group, "panelFontFamily") == null)
			{
				String family;
				switch (legacyStyle)
				{
					case "CLIENT_DEFAULT":
						family = "CLIENT_DEFAULT";
						break;
					// the legacy RuneScape faces map onto OverlayFontFamily's
					// legacy constants, keeping the exact same glyphs
					case "RUNESCAPE_SMALL":
						family = "SMALL";
						break;
					case "RUNESCAPE_BOLD":
						family = "BOLD";
						break;
					case "RUNESCAPE_REGULAR":
						family = "RUNESCAPE";
						break;
					case "SERIF":
						family = "SERIF";
						break;
					case "MONOSPACED":
						family = "MONOSPACED";
						break;
					case "SANS_SERIF":
					default:
						family = "SANS_SERIF";
						break;
				}
				// no weight rewrite: the BOLD legacy family already resolves
				// the bold RuneScape face, exactly like the old panel resolver
				configManager.setConfiguration(group, "panelFontFamily", family);
			}
			configManager.unsetConfiguration(group, "panelFontStyle");
		}
		String legacySize = configManager.getConfiguration(group, "panelTextSize");
		if (legacySize != null)
		{
			if (configManager.getConfiguration(group, "panelFontSize") == null)
			{
				int px;
				switch (legacySize)
				{
					case "SMALL":
						px = 11;
						break;
					case "REGULAR":
					case "DEFAULT":
						px = 12;
						break;
					case "MEDIUM":
						px = 13;
						break;
					case "LARGE":
						px = 14;
						break;
					case "EXTRA_LARGE":
						px = 16;
						break;
					case "HUGE":
						px = 18;
						break;
					case "TINY":
					default:
						px = 10;
						break;
				}
				configManager.setConfiguration(group, "panelFontSize", px);
			}
			configManager.unsetConfiguration(group, "panelTextSize");
		}
	}

	@Override
	protected void shutDown()
	{
		PanelFonts.clear();
		OverlayFonts.clear();
		iconResolver.reset();
		hudOverlay.resetCaches();
		stepNavOverlay.resetClickState();
		ItemGridPanel.clearInventoryBackground();
		// distance-band hysteresis is per-session state: a session disabled at
		// NEAR must not gate the next activation's first objective from FAR
		// guidance (or vice versa) until the first band update tick
		guidanceBand = GuidanceBand.FAR;
		// drop cached container contents so a re-enable or a different account
		// cannot inherit the previous session's worn/carried items
		lastInventoryItems = new Item[0];
		lastWornItems = new Item[0];
		profileReloadPending = false;
		profileReloadAttempts = 0;
		profileProgressReady = false;
		loadedProgressProfileKey = null;
		lastRsProfileKey = null;
		warnedLoggedOutEdit = false;
		active = false;
		lifecycle.end();
		overlayManager.remove(targetOverlay);
		overlayManager.remove(hudOverlay);
		overlayManager.remove(directionArrowOverlay);
		overlayManager.remove(stepNavOverlay);
		overlayManager.remove(dialogOptionOverlay);
		inputHandler.unregister();
		// stop in-flight downloads: guarded callbacks would decline to apply
		// their results anyway, but the requests themselves should not keep
		// running after the plugin is disabled
		guideService.cancelInFlight();
		locationDbDownloader.cancelInFlight();
		if (panel != null)
		{
			panel.dispose();
		}
		clientToolbar.removeNavigation(navButton);
		// deliberately NOT runAsync: this must still run after active goes
		// false, or a pending save would be dropped on shutdown
		executor.execute(locationStore::saveIfDirty);
		bankTagIntegration.cleanup();

		// the plugin instance is reused on re-enable: clear all transient state,
		// including the guide model so a deleted snapshot can't leave stale
		// highlighting/auto-completion running after re-enable
		pinnedStepKey = null;
		pinOrigin = PinOrigin.NONE;
		targetName = null;
		autoSuppressed.clear();
		rewindArmPending.clear();
		tickFailuresLogged.clear();
		// both progress sets, symmetrically: the next activation reloads them
		// from the store, and nothing may answer from the old session's memory
		synchronized (completedSteps)
		{
			completedSteps.clear();
		}
		synchronized (skippedSteps)
		{
			skippedSteps.clear();
		}
		synchronized (undoLock)
		{
			undoCompleted = null;
			undoSkipped = null;
			undoPhaseIds = null;
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
		stepsByKey = Collections.emptyMap();
		structuralParentSteps = Collections.emptySet();
		stepLocationPlans = Collections.emptyMap();
		stepExecutionPlans = Collections.emptyMap();
		activePhaseIds.clear();
		locationPlanCacheGuide = null;
		locationPlanCacheGuideId = null;
		locationPlanCacheCustomRevision = -1;
		locationPlanCacheLocationRevision = -1;
		locationPlanCacheResolverVersion = -1;
		targetNamesNorm = Collections.emptySet();
		clearLocationSuppression();
		locationSnoozeUntilTick = 0;
		waypointArrivalTracker.reset();
		activeWaypointIndexes.clear();
		activeBankCache = null;
		activeBankCacheGuide = null;
		activeBankDirty = true;
		stepHighlightsDirty = true;
		routeSuggestion = null;
		routeDirty = true;
		lastPrefetchKey = null;
		stockTracker.reset();
		activeLocationSummarySnapshot = null;
		waypointStatusSnapshot = null;
		waypointCounterSnapshot = null;
		activeStopIsTransport = false;
		// the closing activation's own cleanup; yields to a newer activation
		runShutdownOnClientThread(this::resetClientThreadState);
	}

	/**
	 * Client-thread state shared across activations (the instance is reused).
	 * Called from the closing activation's shutdown cleanup AND queued by
	 * startUp for the new activation: when a fast disable/re-enable makes the
	 * shutdown cleanup yield, this still runs once under the new generation,
	 * so the new session cannot inherit the old one's arrows, targets,
	 * dialogue position or drawn path.
	 */
	private void resetClientThreadState()
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
		currentStepObjectWords = Collections.emptySet();
		lastHighlightObjective = null;
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
		nextStepKey = null;
		lastRoutePlayer = null;
		lastRouteObjective = null;
		lastPathRefreshFrom = null;
		pathfinder.clear(); // take the drawn path down with the activation
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
		runAsync(() -> selectGuideInternal(guideId, true));
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

		// Cancel any icon-definition scan for the previous guide immediately;
		// the disk read/parse below may take long enough for queued client-thread
		// chunks to otherwise keep working on names that are no longer displayed.
		iconResolver.reset();

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
		stepsByKey = Collections.emptyMap();
		structuralParentSteps = Collections.emptySet();
		stepLocationPlans = Collections.emptyMap();
		stepExecutionPlans = Collections.emptyMap();
		activePhaseIds.clear();
		locationPlanCacheGuide = null;
		locationPlanCacheGuideId = null;
		locationPlanCacheCustomRevision = -1;
		locationPlanCacheLocationRevision = -1;
		locationPlanCacheResolverVersion = -1;
		targetNamesNorm = Collections.emptySet();
		clearLocationSuppression();
		activeBankCache = null;
		activeBankCacheGuide = null;
		activeBankDirty = true;
		stepHighlightsDirty = true;

		currentGuideId = guideId;
		configManager.setConfiguration(HcimGuideConfig.GROUP, "selectedGuide", guideId);
		loadCompletedSteps();
		loadSkippedSteps();
		autoSuppressed.clear();
		rewindArmPending.clear();
		pinStep(null);

		Guide stored = null;
		try
		{
			stored = guideService.loadStored(guideId);
		}
		catch (Exception e)
		{
			log.warn("Failed to load stored guide", e);
		}
		if (stored != null)
		{
			applyGuide(stored, "Guide loaded from local snapshot");
			return;
		}

		final GuideRegistry.Entry finalEntry = entry;
		runOnSwing(() ->
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
			runOnSwing(() ->
				panel.setStatus("This guide has no download source - import it from a file instead"));
			return;
		}
		final String guideId = entry.getId();
		runOnSwing(() -> panel.setStatus("Importing \"" + entry.getTitle() + "\" (one time)..."));

		// hop onto the (single-threaded) executor so a guide switch that is
		// queued there can never be clobbered by a stale fetch result
		java.util.function.BiConsumer<Guide, String> onSuccess = (guide, storageWarning) -> runAsync(() ->
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
			runOnSwing(() -> panel.refreshGuideListLabels());
			// first successful guide import: offer the full location DB once,
			// so a fresh install is two clicks from full functionality
			if (!"true".equals(configManager.getConfiguration(HcimGuideConfig.GROUP, "fullDbPrompted")))
			{
				configManager.setConfiguration(HcimGuideConfig.GROUP, "fullDbPrompted", "true");
				runOnSwing(() -> panel.offerFullDbDownload());
			}
		});
		java.util.function.Consumer<String> onError = error -> runOnSwing(() -> panel.setStatus(error));

		final long requestGeneration = lifecycle.current();
		java.util.function.BooleanSupplier requestCurrent =
			() -> lifecycle.isCurrent(requestGeneration);
		java.util.function.BiConsumer<Guide, String> boundSuccess = boundToActivation(onSuccess);
		java.util.function.Consumer<String> boundError = boundToActivation(onError);
		if (entry.getSourceUrl() != null)
		{
			guideService.fetchUrl(guideId, entry.getSourceUrl(), requestCurrent, boundSuccess, boundError);
		}
		else
		{
			guideService.fetch(guideId, entry.getWikiPage(), requestCurrent, boundSuccess, boundError);
		}
	}

	/**
	 * Adds a guide from a pasted wiki link, then imports it (the add action
	 * itself was the user's explicit consent).
	 */
	void addGuideFromLink(String link)
	{
		runAsync(() ->
		{
			try
			{
				String pageTitle = WikiUrl.pageTitle(link);
				GuideRegistry.Entry entry = guideRegistry.add(WikiUrl.displayName(pageTitle), pageTitle);
				runOnSwing(() -> panel.setGuides(guideRegistry.list(), entry.getId()));
				selectGuideInternal(entry.getId(), false);
				if (!guideService.hasSnapshot(entry.getId()))
				{
					importSelectedFromWiki();
				}
			}
			catch (IllegalArgumentException e)
			{
				runOnSwing(() -> panel.setStatus(e.getMessage()));
			}
		});
	}

	/** Removes a user-added guide from the dropdown (snapshot files are left on disk). */
	void removeSelectedGuide()
	{
		runAsync(() ->
		{
			if (guideRegistry.remove(currentGuideId))
			{
				runOnSwing(() -> panel.setGuides(guideRegistry.list(), GuideRegistry.BUILTIN_ID));
				selectGuideInternal(GuideRegistry.BUILTIN_ID, true);
			}
			else
			{
				runOnSwing(() -> panel.setStatus("The built-in guide can't be removed"));
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
		runAsync(() ->
		{
			GuideRegistry.Entry entry = guideRegistry.add(title, null);
			runOnSwing(() -> panel.setGuides(guideRegistry.list(), entry.getId()));
			selectGuideInternal(entry.getId(), false);
			runOnSwing(() -> panel.setStatus("Importing guide from file..."));
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
			runOnSwing(() ->
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
			runOnSwing(() ->
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
		runOnClientThread(() ->
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
				runOnSwing(() ->
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
				runOnSwing(() ->
					panel.setStatus("Already in sync - no verifiable steps to add"));
				return;
			}
			snapshotBeforeBulk("account sync");
			setCompletedBulk(verified, true);
			final int n = verified.size();
			runOnSwing(() ->
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
		runOnSwing(() -> panel.setStatus("Importing guide from file..."));
		runAsync(() -> importFileOnExecutor(file, guideId, false));
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
			runOnSwing(() -> panel.refreshGuideListLabels());
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
				runOnSwing(() -> panel.setGuides(guideRegistry.list(), currentGuideId));
			}
			runOnSwing(() -> panel.setStatus("Import failed: " + e.getMessage()));
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
		runAsync(() ->
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
				runOnSwing(() ->
				{
					try
					{
						externalActions.copyText(code);
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
				runOnSwing(() -> panel.setStatus("Export failed: " + e.getMessage()));
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
		runAsync(() ->
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
					runOnSwing(() ->
						panel.setStatus("Import the guide first, then import its progress"));
					return;
				}
				if (!destinationGuideId.equals(currentGuideId))
				{
					runOnSwing(() ->
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
					runOnSwing(() ->
						panel.setStatus("Guide changed during progress import - nothing changed"));
					return;
				}
				if (!canMutateProgressStore())
				{
					return;
				}
				snapshotBeforeBulk("progress import");
				sectionNoticeResetPending = true; // store swap, not gameplay
				synchronized (completedSteps)
				{
					completedSteps.clear();
					completedSteps.addAll(imported);
				}
				// A progress code intentionally contains only persisted parent rows.
				// Never carry a local mid-step cursor into a newly imported progress
				// frontier; restart every unfinished parent at its first phase.
				activePhaseIds.clear();
				persistExecutionPhaseCursors();
				autoSuppressed.clear();
				rewindArmPending.clear();
				activeBankDirty = true;
				stepHighlightsDirty = true;
				persistCompletedSteps();
				runOnSwing(() ->
				{
					panel.refreshFromModel();
					panel.setStatus("Progress imported: " + imported.size() + " steps"
						+ (ignored > 0 ? " (ignored " + ignored + " unknown keys)" : "") + note);
				});
			}
			catch (IllegalArgumentException e)
			{
				runOnSwing(() -> panel.setStatus("Import failed: " + e.getMessage()));
			}
			catch (Exception e)
			{
				log.warn("Progress import failed", e);
				runOnSwing(() -> panel.setStatus("Import failed: " + e.getMessage()));
			}
		});
	}

	/**
	 * One-time, user-confirmed download of the full-game NPC location
	 * database. Never overwrites observed/imported positions.
	 */
	void downloadFullLocationDb()
	{
		runOnSwing(() -> panel.setStatus("Downloading full location database (one time)..."));
		// bound to THIS activation, like the guide fetch: a download completing
		// after a disable and re-enable must not merge into the new session
		final long dbGeneration = lifecycle.current();
		locationDbDownloader.download(
			() -> lifecycle.isCurrent(dbGeneration),
			result -> runOnSwing(() ->
				panel.setStatus(!lifecycle.isCurrent(dbGeneration) ? ""
					: "Full database loaded: " + result.added
					+ " new locations (your observed positions kept)"
					+ (result.truncated
						? " — source exceeded the safety cap and was TRUNCATED"
						: ""))),
			error -> runOnSwing(() -> panel.setStatus(error)));
	}

	/** Export the NPC location database to the clipboard as shareable JSON. */
	void exportLocations()
	{
		runAsync(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				String json = locationStore.exportJson();
				final String finalJson = json;
				runOnSwing(() ->
				{
					try
					{
						externalActions.copyText(finalJson);
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
				runOnSwing(() -> panel.setStatus("Export failed: " + e.getMessage()));
			}
		});
	}

	/**
	 * Reads the system clipboard OFF the EDT - getData can block (notably on
	 * X11 when the clipboard owner stalls) and must never freeze the client
	 * UI - then hands the text to the consumer on the executor thread, or
	 * null when the clipboard holds no text. Call only after the user
	 * explicitly confirmed the paste action; the clipboard is never read
	 * speculatively.
	 */
	void readClipboardText(java.util.function.Consumer<String> onText)
	{
		runAsync(() ->
		{
			if (!active)
			{
				return;
			}
			onText.accept(externalActions.readText());
		});
	}

	/**
	 * Open a guide video link. Delegates to {@link GuideExternalActions}, which
	 * re-validates and normalizes the URL before anything is opened.
	 */
	void openVideoUrl(String url)
	{
		externalActions.openVideoUrl(url);
	}

	/** Import NPC locations from shared JSON (community dataset or a friend's export). */
	void importLocations(String json)
	{
		runAsync(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				int count = locationStore.importJson(json);
				locationStore.saveIfDirty();
				runOnSwing(() ->
					panel.setStatus("Imported " + count + " NPC locations"));
			}
			catch (IllegalArgumentException e)
			{
				runOnSwing(() -> panel.setStatus("Import failed: " + e.getMessage()));
			}
			catch (Exception e)
			{
				log.warn("Location import failed", e);
				runOnSwing(() -> panel.setStatus("Import failed: " + e.getMessage()));
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
		runAsync(() ->
		{
			if (!active)
			{
				return;
			}
			GuideService.RestoreResult restored = guideService.restorePrevious(guideId);
			if (restored != null && guideId.equals(currentGuideId))
			{
				applyGuide(restored.guide, restored.warning != null
					? restored.warning
					: "Previous guide snapshot restored");
			}
			else if (restored == null)
			{
				runOnSwing(() -> panel.setStatus("No previous snapshot to restore"));
			}
		});
	}

	/**
	 * Store the guide and precompute everything derivable from step text
	 * (auto-completion conditions, NPC target names) so per-tick code and
	 * panel rebuilds only do map lookups, never regex work.
	 */
	/** Log the loaded guide's shape once, so a log shows what was actually parsed. */
	private void logGuideLoaded(Guide guide)
	{
		if (guide == null)
		{
			log.info("Guide Overlay: no guide loaded");
			return;
		}
		int steps = 0;
		int banks = 0;
		int done = 0;
		for (GuideEpisode e : guide.getEpisodes())
		{
			for (GuideBank b : e.getBanks())
			{
				banks++;
				steps += b.getSteps().size();
				done += countCompleted(b.getSteps());
			}
		}
		GuideRegistry.Entry entry = guideRegistry.byId(currentGuideId);
		log.info("Guide Overlay loaded: builtin={}, {} banks, {} steps, {} already completed",
			entry != null && entry.isBuiltin(), banks, steps, done);
	}

	private void applyGuide(Guide guide, String status)
	{
		// Item-name caches are scoped to the active guide. This also invalidates
		// any client-thread definition scan still queued for the previous model.
		iconResolver.reset();
		logGuideLoaded(guide);
		Map<String, StepCondition> cond = new HashMap<>();
		Map<String, List<ItemReq>> items = new HashMap<>();
		Map<String, int[]> dialogSeqs = new HashMap<>();
		Map<String, List<String>> dialogNpcMap = new HashMap<>();
		Map<String, Set<String>> dialogObjMap = new HashMap<>();
		Map<String, String> targets = new HashMap<>();
		Map<String, GuideStep> parsedSteps = new HashMap<>();
		Set<String> parentSteps = new HashSet<>();
		Set<String> namesNorm = new HashSet<>();
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank bank : ep.getBanks())
			{
				List<GuideStep> bankSteps = bank.getSteps();
				for (int i = 0; i + 1 < bankSteps.size(); i++)
				{
					GuideStep current = bankSteps.get(i);
					if (bankSteps.get(i + 1).getDepth() > current.getDepth())
					{
						parentSteps.add(current.getKey());
					}
				}
				for (GuideStep step : bankSteps)
				{
					parsedSteps.put(step.getKey(), step);
					StepCondition c = ConditionParser.parse(step.getText());
					if (c != null)
					{
						cond.put(step.getKey(), c);
					}
					// One shared projection is used by the panel, preload, and audit.
					// This includes condition-backed item steps and JSON guide
					// display-only "(Items: ...)" suffixes.
					List<ItemReq> displayItems =
						StepItemRequirements.displayFor(step.getText(), c);
					if (displayItems != null && !displayItems.isEmpty())
					{
						items.put(step.getKey(), displayItems);
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
		Map<String, StepLocationPlan> locations = buildStepLocations(guide, targets);
		Map<String, StepExecutionPlan> execution = StepExecutionPlanner.buildPlans(
			guide, placeDirectory, transportResolver, locationStore::lookup, baseLocationPlans);
		conditions = cond;
		stepItems = items;
		dialogSequences = dialogSeqs;
		dialogNpcs = dialogNpcMap;
		dialogObjectWords = dialogObjMap;
		stepTargets = targets;
		stepsByKey = Collections.unmodifiableMap(parsedSteps);
		structuralParentSteps = Collections.unmodifiableSet(parentSteps);
		stepLocationPlans = locations;
		stepExecutionPlans = execution;
		loadExecutionPhaseCursors(execution);
		loadWaypointIndexes(locations);
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
		if (guide.isTruncated())
		{
			// never present a capped import as the complete guide
			structure += " — source exceeded a safety limit and was TRUNCATED";
		}
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
		runOnSwing(() -> panel.setGuide(guide, displayStatus));
	}

	/** Build direct/inferred plans, then apply profile-scoped manual overrides. */
	private Map<String, StepLocationPlan> buildStepLocations(Guide guide, Map<String, String> targets)
	{
		long customRevision = customLocationStore.getRevision();
		int locationRevision = locationStore.getRevision();
		if (locationPlanCacheGuide == guide
			&& java.util.Objects.equals(locationPlanCacheGuideId, currentGuideId)
			&& locationPlanCacheCustomRevision == customRevision
			&& locationPlanCacheLocationRevision == locationRevision
			&& locationPlanCacheResolverVersion == StepLocationPlanner.RESOLVER_VERSION)
		{
			return stepLocationPlans;
		}
		Map<String, StepLocationPlan> built = new HashMap<>(StepLocationPlanner.buildPlans(
			guide, targets, placeDirectory, transportResolver, locationStore::lookup));
		baseLocationPlans = Collections.unmodifiableMap(new HashMap<>(built));
		// custom overrides: fetched in ONE store parse - per-step getPlan()
		// would deserialize the whole stored blob once per step
		Map<String, StepLocationPlan> customPlans =
			customLocationStore.getPlansForGuide(currentGuideId);
		if (!customPlans.isEmpty())
		{
			for (GuideEpisode episode : guide.getEpisodes())
			{
				for (GuideBank bank : episode.getBanks())
				{
					for (GuideStep step : bank.getSteps())
					{
						StepLocationPlan custom = customPlans.get(step.getKey());
						if (custom != null)
						{
							built.put(step.getKey(), custom);
						}
					}
				}
			}
		}
		Map<String, StepLocationPlan> immutable = Collections.unmodifiableMap(built);
		locationPlanCacheGuide = guide;
		locationPlanCacheGuideId = currentGuideId;
		locationPlanCacheCustomRevision = customRevision;
		locationPlanCacheLocationRevision = locationRevision;
		locationPlanCacheResolverVersion = StepLocationPlanner.RESOLVER_VERSION;
		return immutable;
	}

	private void loadWaypointIndexes(Map<String, StepLocationPlan> plans)
	{
		activeWaypointIndexes.clear();
		if (!config.persistWaypointIndex())
		{
			return;
		}
		// one store parse for the whole guide, not one per plan entry
		Map<String, Integer> saved = customLocationStore.getActiveIndexesForGuide(currentGuideId);
		for (Map.Entry<String, StepLocationPlan> entry : plans.entrySet())
		{
			// The legacy index store is keyed only by parent step. Reusing it for a
			// different virtual phase can select the wrong stop after restart, so
			// virtual plans deliberately restart their current phase at waypoint 1.
			StepExecutionPlan execution = activeExecutionPlan(entry.getKey());
			if (execution != null && execution.isVirtualized())
			{
				continue;
			}
			Integer index = saved.get(entry.getKey());
			if (index != null && index > 0 && index < entry.getValue().size())
			{
				activeWaypointIndexes.put(entry.getKey(), index);
			}
		}
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
	 * that cursor and moves back to it. Backward is a REWIND, not a veto:
	 * unlike unticking the checkbox, the step stays eligible to complete
	 * again - immediately on a fresh confirmed arrival, and for conditions
	 * once the condition is next observed unmet. Pure checklist bookkeeping:
	 * no game input is ever synthesized. Called from the AWT input thread;
	 * all state it touches is thread-safe.
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
			resetGuidanceForNavigation();
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
			// navigation REWIND, not a veto: the step is unchecked for review
			// and stays eligible for automatic completion on a fresh arrival.
			// A stale veto from an earlier untick is cleared for the same
			// reason - stepping back to a step means the user wants to run it.
			// CONDITION completion re-arms only on the condition's next false
			// edge, or a still-true quest/skill condition would snap the step
			// straight back within one evaluation cycle.
			setCompleted(lastDone.getKey(), false, false);
			autoSuppressed.remove(lastDone.getKey());
			rewindArmPending.add(lastDone.getKey());
			// the rewound step restarts at its first phase and first waypoint;
			// setCompleted already reset the in-memory cursors, so also persist
			// waypoint 1 - an explicit backward navigation must not resume from
			// a remembered later waypoint
			if (config.persistWaypointIndex() && currentGuideId != null)
			{
				try
				{
					customLocationStore.setActiveIndex(currentGuideId, lastDone.getKey(), 0);
				}
				catch (RuntimeException e)
				{
					// no character profile yet (early-login window): the store
					// refuses global writes by design. The in-memory rewind
					// already happened; losing only the persisted index must
					// not throw on the input thread or skip the guidance reset.
					log.debug("Waypoint index persist unavailable during rewind", e);
				}
			}
			resetGuidanceForNavigation();
		}
		runOnSwing(() ->
		{
			if (active && panel != null)
			{
				panel.refreshFromModel();
			}
		});
	}

	/**
	 * Atomic guidance hand-off for a Previous/Next navigation: any pin - user
	 * or automatic - stops controlling guidance, and every piece of per-step
	 * guidance state (arrival tracking, native and colored arrows, far
	 * target, world-map marker, drawn path, route suggestion, dialogue
	 * guidance, highlight scope, hide state) is torn down NOW rather than
	 * waiting for change detection. The next game tick resolves the new
	 * current step's objective from scratch through the one authoritative
	 * guidance key.
	 */
	private void resetGuidanceForNavigation()
	{
		pinnedStepKey = null;
		pinOrigin = PinOrigin.NONE;
		targetName = null;
		clearLocationSuppression();
		routeDirty = true;
		stepHighlightsDirty = true;
		hudItemsKey = null;
		runOnClientThread(() ->
		{
			waypointArrivalTracker.reset();
			clearArrowOnClientThread();
			targetNpc = null;
			clearFarTarget();
			dialogStepKey = null;
			dialogSeq = null;
			dialogPos = 0;
			pathfinder.clear();
		});
	}

	/** Pin the next unchecked step that has a trackable NPC, item, or place. */
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
		final String target = getStepTarget(next.getKey());
		runOnSwing(() ->
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

	/** Hit test for the per-step show/hide location-guide button. */
	private boolean hitLocationToggle(java.awt.Point p)
	{
		if (!active || currentGuide == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		HcimGuideConfig.ArrowMode mode = config.navArrows();
		if (mode == HcimGuideConfig.ArrowMode.ATTACHED)
		{
			return hudOverlay.hitLocationToggle(p);
		}
		if (mode == HcimGuideConfig.ArrowMode.FLOATING)
		{
			return stepNavOverlay.hitLocationToggle(p);
		}
		return mode == HcimGuideConfig.ArrowMode.HIDDEN && hudOverlay.hitLocationToggle(p);
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
		// the resolver revision is part of the key: a name that resolves late
		// (full-database scan) changes the revision, which re-resolves this
		// same step's icons on the next tick without any explicit invalidation
		String hudKey = first.getKey() + "@r" + iconResolver.revision();
		if (hudKey.equals(hudItemsKey))
		{
			return;
		}
		hudItemsKey = hudKey;
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
		runAsync(() ->
		{
			if (!active)
			{
				return;
			}
			int[] ids = iconResolver.resolve(reqs);
			if (hudItemsGen.get() == gen)
			{
				hudItems = new HudItems(reqs, ids);
				// close the check-then-act window: a clearHudItems (shutdown,
				// config-off) that bumped the generation between the check and
				// the write above must win - re-verify and take it back down
				if (hudItemsGen.get() != gen)
				{
					hudItems = null;
				}
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
		boolean complete = true;
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
			if (itemSteps >= 200)
			{
				// hostile-input bound only: the real guides max out at 66
				// item-bearing steps per section (BRUHsailer) - unlike the
				// bank tag's 12, readiness must examine EVERY requirement or
				// none, so past the cap it fails closed rather than guessing
				// "ready" with requirements unexamined.
				complete = false;
				break;
			}
			itemSteps++;
			for (ItemReq req : reqs)
			{
				ItemIconResolver.ResolutionState state = iconResolver.stateOf(req);
				if (state == ItemIconResolver.ResolutionState.UNRESOLVABLE)
				{
					continue; // proven free text ("2 Food") - ignore
				}
				if (state == ItemIconResolver.ResolutionState.CONCEPT)
				{
					// "Combat Gear" names no item, so it can never be found in
					// the inventory. Treating it as outstanding kept readiness
					// PENDING forever and the trip never reported ready.
					continue;
				}
				if (state == ItemIconResolver.ResolutionState.PENDING)
				{
					// might be a real item whose resolution hasn't finished -
					// the truth isn't known, so never claim (or sound) ready.
					// The periodic scan re-kick (game tick) guarantees this
					// resolves to RESOLVED or UNRESOLVABLE within moments.
					complete = false;
					break outer;
				}
				any = true;
				if (inventory.countOf(req) < req.getCompletionQuantity())
				{
					all = false;
					break outer;
				}
			}
		}
		boolean ready = any && all && complete;
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
				if (config.soundSource() == HcimGuideConfig.SoundSource.PLUGIN)
				{
					chimePlayer.playTripReady(config.pluginSoundVolume());
				}
				else
				{
					client.playSoundEffect(TRIP_READY_SOUND);
				}
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
				if (config.soundSource() == HcimGuideConfig.SoundSource.PLUGIN)
				{
					chimePlayer.playSuccess(config.pluginSoundVolume());
				}
				else
				{
					client.playSoundEffect(SUCCESS_SOUND);
				}
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
		int arrowsW = StepNavOverlay.totalWidth(true);
		stepNavOverlay.setPreferredLocation(new java.awt.Point(
			Math.max(0, (w - arrowsW) / 2),
			Math.max(0, h / 2 - StepNavOverlay.BUTTON_H / 2)));
		overlayManager.saveOverlay(stepNavOverlay);
	}

	/** Label shown by the sidebar pin button for any locatable step. */
	String getStepTarget(String stepKey)
	{
		StepLocationHint hint = activeWaypoint(stepKey);
		if (hint != null && hint.isPreferredOverEntity())
		{
			return hint.getLabel();
		}
		String target = activeStepTarget(stepKey);
		if (target != null)
		{
			return target;
		}
		return hint == null ? null : hint.getLabel();
	}

	InventorySnapshot getInventorySnapshot()
	{
		return inventory;
	}

	/** Resolve item ids for a grid off the EDT, then apply icons. */
	void resolveItemIcons(ItemGridPanel grid)
	{
		runAsync(() ->
		{
			List<ItemReq> reqs = grid.getItems();
			int[] ids = iconResolver.resolve(reqs);
			grid.applyIcons(itemManager, spriteManager, reqs, ids);
			// names the price search can't see (untradeables like talismans
			// and quest amulets) get one chunked full-database scan. The scan
			// bumps the resolver revision, which the HUD key and bank-tag
			// signature embed - so those refresh themselves on the next tick;
			// only the already-built Swing grids need an explicit re-resolve
			if (iconResolver.hasPendingScan())
			{
				iconResolver.scanFullDatabase(() ->
					runOnSwing(() -> panel.reresolveIcons()));
			}
		});
	}

	// ------------------------------------------------------------------ progress

	/**
	 * Refuse logged-in progress mutations until ConfigManager has selected the
	 * character-specific store. Logged-out checklist browsing may still edit the
	 * documented shared store, but a login-order race must never write there.
	 */
	private boolean canMutateProgressStore()
	{
		if (!config.perCharacterProgress() || configManager.getRSProfileKey() != null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return true;
		}
		profileReloadPending = true;
		profileProgressReady = false;
		runOnSwing(() ->
		{
			if (active && panel != null)
			{
				panel.setStatus("Loading character progress - change not saved yet");
			}
		});
		return false;
	}

	boolean isCompleted(String stepKey)
	{
		synchronized (completedSteps)
		{
			return completedSteps.contains(stepKey);
		}
	}

	void setCompleted(String stepKey, boolean completed)
	{
		// a direct untick (checkbox) is an automatic-completion veto
		setCompleted(stepKey, completed, !completed);
	}

	/**
	 * @param vetoAutoCompletion whether an untick expresses "stop fighting
	 *     me" (checkbox, bulk clear) as opposed to a NAVIGATION rewind, which
	 *     unchecks the step for review but leaves it eligible to complete
	 *     again on a fresh confirmed arrival.
	 */
	private void setCompleted(String stepKey, boolean completed, boolean vetoAutoCompletion)
	{
		if (!canMutateProgressStore())
		{
			return;
		}
		if (!completed && vetoAutoCompletion)
		{
			// manual untick: never fight the user by re-auto-completing it
			autoSuppressed.add(stepKey);
		}
		if (completed)
		{
			// completing (by any means) resolves a pending rewind re-arm
			rewindArmPending.remove(stepKey);
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
		resetExecutionPhase(stepKey, false);
		persistExecutionPhaseCursors();
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
		runOnClientThread(() ->
		{
			if (!active)
			{
				return;
			}
			try
			{
				if (config.soundSource() == HcimGuideConfig.SoundSource.PLUGIN)
				{
					chimePlayer.playSuccess(config.pluginSoundVolume());
				}
				else
				{
					client.playSoundEffect(SUCCESS_SOUND);
				}
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
		if (!canMutateProgressStore())
		{
			return;
		}
		String pinnedChanged = null;
		List<String> phaseResetKeys = new ArrayList<>();
		synchronized (completedSteps)
		{
			for (GuideStep s : steps)
			{
				phaseResetKeys.add(s.getKey());
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
		for (String key : phaseResetKeys)
		{
			resetExecutionPhase(key, false);
		}
		persistExecutionPhaseCursors();
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
		if (!canMutateProgressStore())
		{
			return;
		}
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
		resetExecutionPhase(stepKey, false);
		persistExecutionPhaseCursors();
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
				skippedSteps.addAll(parseStoredKeySet(json, "skipped steps"));
			}
		}
		activeBankDirty = true;
		stepHighlightsDirty = true;
	}

	private void persistSkippedSteps()
	{
		if (!canMutateProgressStore())
		{
			return;
		}
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


	// ------------------------------------------------------------------ virtual execution phases

	private String executionPhaseKey()
	{
		return "executionPhases." + currentGuideId;
	}

	private void loadExecutionPhaseCursors(Map<String, StepExecutionPlan> plans)
	{
		activePhaseIds.clear();
		String json;
		if (config.perCharacterProgress() && configManager.getRSProfileKey() != null)
		{
			// A mid-step cursor is transient character state. Unlike completed
			// parent rows it must never be seeded from the logged-out/shared store.
			json = configManager.getRSProfileConfiguration(HcimGuideConfig.GROUP,
				executionPhaseKey());
		}
		else
		{
			json = configManager.getConfiguration(HcimGuideConfig.GROUP, executionPhaseKey());
		}
		if (json == null || json.isEmpty())
		{
			return;
		}
		if (json.length() > MAX_STORED_STATE_CHARS)
		{
			log.warn("Saved virtual execution phases exceed the safety cap and were ignored");
			return;
		}
		try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
			new java.io.StringReader(json)))
		{
			reader.beginObject();
			int accepted = 0;
			while (reader.hasNext())
			{
				String stepKey = reader.nextName();
				if (reader.peek() != com.google.gson.stream.JsonToken.STRING)
				{
					reader.skipValue();
					continue;
				}
				String phaseId = reader.nextString();
				if (accepted >= MAX_STORED_STATE_ENTRIES
					|| !validStoredStateKey(stepKey) || !validStoredStateKey(phaseId))
				{
					continue;
				}
				StepExecutionPlan plan = plans.get(stepKey);
				if (plan != null && !isStepDone(stepKey) && plan.indexOfId(phaseId) >= 0)
				{
					activePhaseIds.put(stepKey, phaseId);
					accepted++;
				}
			}
			reader.endObject();
		}
		catch (Exception e)
		{
			log.warn("Could not parse saved virtual execution phases", e);
		}
	}

	private void persistExecutionPhaseCursors()
	{
		if (!canMutateProgressStore())
		{
			return;
		}
		String json = gson.toJson(new HashMap<>(activePhaseIds));
		if (config.perCharacterProgress() && configManager.getRSProfileKey() != null)
		{
			// same cross-profile guard as writeProgressJson: phase cursors and
			// completions must not desynchronize across a character switch
			if (!java.util.Objects.equals(loadedProgressProfileKey, configManager.getRSProfileKey()))
			{
				profileReloadPending = true;
				return;
			}
			configManager.setRSProfileConfiguration(HcimGuideConfig.GROUP, executionPhaseKey(), json);
		}
		else
		{
			configManager.setConfiguration(HcimGuideConfig.GROUP, executionPhaseKey(), json);
		}
	}

	private int activeExecutionPhaseIndex(String stepKey, StepExecutionPlan plan)
	{
		if (stepKey == null || plan == null || plan.size() == 0)
		{
			return 0;
		}
		int saved = plan.indexOfId(activePhaseIds.get(stepKey));
		return saved < 0 ? 0 : saved;
	}

	private StepExecutionPlan activeExecutionPlan(String stepKey)
	{
		if (stepKey == null)
		{
			return null;
		}
		// Custom parent routes have one cursor for the whole original row. Mixing
		// that cursor with generated phase-local routes would replay the custom
		// route after every phase. Preserve the user's authoritative route and
		// fall back to legacy parent semantics until the override is cleared.
		if (hasManualOrAuthoredWaypoint(stepLocationPlans.get(stepKey)))
		{
			return null;
		}
		return stepExecutionPlans.get(stepKey);
	}

	private StepExecutionPhase activeExecutionPhase(String stepKey)
	{
		StepExecutionPlan plan = activeExecutionPlan(stepKey);
		return plan == null ? null : plan.get(activeExecutionPhaseIndex(stepKey, plan));
	}

	private String activeStepTarget(String stepKey)
	{
		StepExecutionPhase phase = activeExecutionPhase(stepKey);
		String parent = stepTargets.get(stepKey);
		if (phase == null)
		{
			return parent;
		}
		// A generated phase with no target of its own must not HIDE the parent's.
		// Phases exist to narrow guidance, never to remove it: a signal-less
		// phase previously blanked the target and left the row with no guidance
		// at all, which is worse than the behaviour before phases existed.
		String phaseTarget = phase.getEntityTarget();
		return phaseTarget != null ? phaseTarget : parent;
	}

	private StepCondition activeStepCondition(String stepKey)
	{
		StepExecutionPhase phase = activeExecutionPhase(stepKey);
		// Deliberately NO parent fallback here, unlike target and location.
		// StepExecutionPlanner rejects any plan whose parent condition is not
		// owned by some phase, so a null condition on THIS phase means it
		// belongs to another one. Falling back would evaluate it on the wrong
		// phase and could complete the row early.
		return phase != null ? phase.getCondition() : conditions.get(stepKey);
	}

	private boolean advanceExecutionPhase(String stepKey, String source)
	{
		StepExecutionPlan plan = activeExecutionPlan(stepKey);
		if (plan == null || plan.size() <= 1)
		{
			return false;
		}
		int current = activeExecutionPhaseIndex(stepKey, plan);
		if (current >= plan.size() - 1)
		{
			return false;
		}
		StepExecutionPhase next = plan.get(current + 1);
		boolean guided = stepKey.equals(guidedStepKey());
		activePhaseIds.put(stepKey, next.getId());
		activeWaypointIndexes.remove(stepKey);
		if (config.persistWaypointIndex())
		{
			customLocationStore.setActiveIndex(currentGuideId, stepKey, 0);
		}
		persistExecutionPhaseCursors();
		routeDirty = true;
		stepHighlightsDirty = true;
		hudItemsKey = null;
		if (guided)
		{
			// Auto-completion evaluates every row in the active bank. A later row's
			// preparation condition may therefore advance before the row currently
			// being followed. Its private cursor can change, but it must not clear or
			// retarget the active row's arrow, route, dialogue, or suppression state.
			waypointArrivalTracker.reset();
			clearLocationSuppression();
			targetNpc = null;
			targetName = activeStepTarget(stepKey);
			dialogStepKey = null;
			dialogSeq = null;
			dialogPos = 0;
			clearArrowOnClientThread();
			clearFarTarget();
			pathfinder.clear();
		}
		// Keep normal logs free of user-imported guide identifiers and text.
		log.debug("Guide Overlay: advanced virtual phase via {} phase={}/{}",
			source, current + 2, plan.size());
		runOnSwing(() ->
		{
			if (active && panel != null)
			{
				panel.refreshFromModel();
				if (guided)
				{
					panel.onPinChanged(getStepTarget(stepKey));
					panel.setStatus("Step phase " + (current + 2) + "/" + plan.size()
						+ ": " + Text.removeTags(shorten(next.getText())));
				}
			}
		});
		return true;
	}

	private void resetExecutionPhase(String stepKey, boolean persist)
	{
		if (stepKey == null)
		{
			return;
		}
		if (activePhaseIds.remove(stepKey) != null && persist)
		{
			persistExecutionPhaseCursors();
		}
		activeWaypointIndexes.remove(stepKey);
	}

	String getExecutionPhaseStatus(String stepKey)
	{
		StepExecutionPlan plan = activeExecutionPlan(stepKey);
		if (plan == null || plan.size() <= 1)
		{
			return null;
		}
		int index = activeExecutionPhaseIndex(stepKey, plan);
		return "Phase " + (index + 1) + "/" + plan.size();
	}

	// ------------------------------------------------------------------ bulk undo

	private final Object undoLock = new Object();
	private Set<String> undoCompleted;
	private Set<String> undoSkipped;
	private Map<String, String> undoPhaseIds;
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
		Map<String, String> phaseIds = new HashMap<>(activePhaseIds);
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
			undoPhaseIds = phaseIds;
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
		if (!canMutateProgressStore())
		{
			return false;
		}
		Set<String> completed;
		Set<String> skipped;
		Map<String, String> phaseIds;
		String label;
		synchronized (undoLock)
		{
			if (undoCompleted == null || !currentGuideId.equals(undoGuideId))
			{
				runOnSwing(() -> panel.setStatus("Nothing to undo"));
				return false;
			}
			completed = undoCompleted;
			skipped = undoSkipped;
			phaseIds = undoPhaseIds == null ? Collections.emptyMap() : undoPhaseIds;
			label = undoLabel;
			undoCompleted = null;
			undoSkipped = null;
			undoPhaseIds = null;
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
		activePhaseIds.clear();
		for (Map.Entry<String, String> entry : phaseIds.entrySet())
		{
			StepExecutionPlan plan = stepExecutionPlans.get(entry.getKey());
			if (plan != null && !isStepDone(entry.getKey())
				&& plan.indexOfId(entry.getValue()) >= 0)
			{
				activePhaseIds.put(entry.getKey(), entry.getValue());
			}
		}
		persistCompletedSteps();
		persistSkippedSteps();
		persistExecutionPhaseCursors();
		activeBankDirty = true;
		stepHighlightsDirty = true;
		sectionNoticeResetPending = true;
		final String doneLabel = label;
		runOnSwing(() ->
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
		if (!canMutateProgressStore())
		{
			return;
		}
		if (config.perCharacterProgress() && configManager.getRSProfileKey() != null)
		{
			// During a character switch ConfigManager swaps the RS profile key
			// before RuneScapeProfileChanged reloads the in-memory sets; a write
			// in that window would stamp the OLD character's progress onto the
			// NEW character's store. Refuse and let the reload path re-sync.
			if (!java.util.Objects.equals(loadedProgressProfileKey, configManager.getRSProfileKey()))
			{
				profileReloadPending = true;
				log.warn("Guide Overlay: progress write skipped - store still bound "
					+ "to the previous character; reload pending");
				return;
			}
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
				runOnSwing(() ->
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
				completedSteps.addAll(parseStoredKeySet(json, "progress"));
			}
		}
		activeBankDirty = true;
		stepHighlightsDirty = true;
	}

	private Set<String> parseStoredKeySet(String json, String label)
	{
		if (json.length() > MAX_STORED_STATE_CHARS)
		{
			log.warn("Saved {} exceed the safety cap and were ignored", label);
			return Collections.emptySet();
		}
		Set<String> bounded = new HashSet<>();
		try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
			new java.io.StringReader(json)))
		{
			reader.beginArray();
			while (reader.hasNext())
			{
				if (reader.peek() != com.google.gson.stream.JsonToken.STRING)
				{
					reader.skipValue();
					continue;
				}
				String key = reader.nextString();
				if (bounded.size() < MAX_STORED_STATE_ENTRIES && validStoredStateKey(key))
				{
					bounded.add(key);
				}
			}
			reader.endArray();
			return bounded;
		}
		catch (Exception e)
		{
			log.warn("Could not parse saved {}", label, e);
			return Collections.emptySet();
		}
	}

	private static boolean validStoredStateKey(String key)
	{
		return key != null && !key.isEmpty() && key.length() <= MAX_STORED_STATE_KEY_CHARS;
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
		String managerKey = configManager.getRSProfileKey();
		// profile KEYS are per-account identifiers; log only whether they changed
		log.debug("Guide Overlay: RuneScape profile event, hadPrevious={} hasNew={} managerKeyPresent={}",
			event.getPreviousProfile() != null, event.getNewProfile() != null,
			managerKey != null);
		if (!config.perCharacterProgress())
		{
			reloadProgressStore("RuneScapeProfileChanged/shared");
			return;
		}
		if (event.getNewProfile() == null || managerKey == null)
		{
			profileProgressReady = false;
			profileReloadPending = client.getGameState() == GameState.LOGGED_IN;
			profileReloadAttempts = 0;
			return;
		}

		String key = progressKey();
		String profileProgress = configManager.getRSProfileConfiguration(
			HcimGuideConfig.GROUP, key);
		if (profileProgress == null && GuideRegistry.BUILTIN_ID.equals(currentGuideId))
		{
			profileProgress = configManager.getRSProfileConfiguration(
				HcimGuideConfig.GROUP, HcimGuideConfig.COMPLETED_STEPS_KEY);
		}
		if (profileProgress == null)
		{
			String shared = configManager.getConfiguration(HcimGuideConfig.GROUP, key);
			if (shared == null && GuideRegistry.BUILTIN_ID.equals(currentGuideId))
			{
				shared = configManager.getConfiguration(HcimGuideConfig.GROUP,
					HcimGuideConfig.COMPLETED_STEPS_KEY);
			}
			if (shared != null && !shared.isEmpty())
			{
				configManager.setRSProfileConfiguration(HcimGuideConfig.GROUP, key, shared);
			}
		}

		String skey = skippedKey();
		if (configManager.getRSProfileConfiguration(HcimGuideConfig.GROUP, skey) == null)
		{
			String sharedSkip = configManager.getConfiguration(HcimGuideConfig.GROUP, skey);
			if (sharedSkip != null && !sharedSkip.isEmpty())
			{
				configManager.setRSProfileConfiguration(HcimGuideConfig.GROUP, skey, sharedSkip);
			}
		}
		reloadProgressStore("RuneScapeProfileChanged");
	}

	/**
	 * The active RuneLite CONFIG profile changed underneath us: a profile
	 * switch in the profile panel, or the cloud-synced profile merging in
	 * when the user signs into their RuneLite account mid-session (this is
	 * how progress follows the account across PCs - both regular and
	 * per-character keys live in synced config). The in-memory progress
	 * must reload from the new store immediately: keeping stale sets would
	 * overwrite the freshly synced values on the next persist.
	 */
	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		// the newly activated config profile may still hold pre-parity panel
		// font keys that startUp's one-time migration never saw
		migratePanelFontConfig();
		reloadProgressStore("RuneLite ProfileChanged");
		customLocationStore.invalidate();
		// Location pins are config-profile scoped too, so always rebuild them even
		// when the two progress sets happen to compare equal.
		runAsync(() ->
		{
			if (active)
			{
				rebuildLocationPlans();
			}
		});
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
			lastWornItems = container.getItems();
			rebuildInventorySnapshot();
			return;
		}
		if (event.getContainerId() == InventoryID.BANK)
		{
			stockTracker.update(BankStockTracker.SRC_BANK, container.getItems());
			// persist off the client thread so "in bank" survives restarts;
			// active-guarded so a queued save can't fire after shutDown and
			// persist the reset (empty) tracker over real data
			runAsync(() ->
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
		stockTracker.update(BankStockTracker.SRC_INVENTORY, container.getItems());
		lastInventoryItems = container.getItems();
		rebuildInventorySnapshot();
	}

	/**
	 * Rebuild the presence snapshot from the union of carried and worn items.
	 *
	 * <p>Keyed by normalized and singular-candidate name on the client thread
	 * (about 40 items), so every downstream presence check is a map lookup.</p>
	 */
	private void rebuildInventorySnapshot()
	{
		Map<String, Integer> byNorm = new HashMap<>();
		Map<String, Integer> bySing = new HashMap<>();
		Map<ItemCategory, Integer> byCategory = new java.util.EnumMap<>(ItemCategory.class);
		accumulateItems(lastInventoryItems, byNorm, bySing, byCategory);
		accumulateItems(lastWornItems, byNorm, bySing, byCategory);
		final InventorySnapshot snapshot = new InventorySnapshot(byNorm, bySing, byCategory);
		inventory = snapshot;
		runOnSwing(() -> panel.updateInventory(snapshot));
	}

	private void accumulateItems(Item[] items, Map<String, Integer> byNorm,
		Map<String, Integer> bySing, Map<ItemCategory, Integer> byCategory)
	{
		if (items == null)
		{
			return;
		}
		for (Item item : items)
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			int canonicalId = itemManager.canonicalize(item.getId());
			ItemComposition comp = itemManager.getItemComposition(canonicalId);
			// a null composition is possible for an id the cache does not know;
			// letting it through would NPE out of the @Subscribe handler and
			// silently stop every item-based presence check from then on
			if (comp == null)
			{
				continue;
			}
			String name = comp.getName();
			if (name == null || "null".equals(name))
			{
				continue;
			}
			byNorm.merge(Names.normalize(name), item.getQuantity(), Integer::sum);
			// index under every candidate form so a requirement written any
			// way round still finds this stack; the snapshot dedupes on the
			// requirement side so one stack is never counted twice
			for (String candidate : Names.singularCandidates(name))
			{
				bySing.merge(candidate, item.getQuantity(), Integer::sum);
			}
			for (ItemCategory category : ItemCategory.values())
			{
				if (category.matches(comp.getName(), comp.getInventoryActions()))
				{
					byCategory.merge(category, item.getQuantity(), Integer::sum);
				}
			}
		}
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
				runOnSwing(() -> panel.setTargetStatus(name, false));
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

	/**
	 * Re-read profile-scoped progress once the RS profile exists.
	 *
	 * <p>Cheap and idempotent: it compares complete completed/skipped snapshots
	 * and semantic virtual-phase cursors, so equal counts with different content
	 * cannot hide a real store change.</p>
	 */
	/** Set on login, cleared once profile-scoped progress has actually loaded. */
	private volatile boolean profileReloadPending;
	/** False only during the short logged-in interval before the character store is ready. */
	private volatile boolean profileProgressReady;
	/** Profile key whose progress is currently represented by the in-memory sets. */
	private volatile String loadedProgressProfileKey;
	private int profileReloadAttempts;
	private static final int PROFILE_CREATE_ATTEMPT_TICK = 3;
	private static final int PROFILE_RELOAD_WARNING_TICK = 20;

	private Set<String> completedSnapshot()
	{
		synchronized (completedSteps)
		{
			return new HashSet<>(completedSteps);
		}
	}

	private Set<String> skippedSnapshot()
	{
		synchronized (skippedSteps)
		{
			return new HashSet<>(skippedSteps);
		}
	}

	/**
	 * Atomically switch the in-memory model to whichever progress store is now
	 * selected by ConfigManager. RuneScapeProfileChanged is the authoritative
	 * path; the game-tick retry only covers enable/login ordering and creation
	 * of a brand-new RS profile.
	 */
	private void reloadProgressStore(String source)
	{
		String profileKey = config.perCharacterProgress()
			? configManager.getRSProfileKey() : null;
		if (config.perCharacterProgress() && profileKey == null)
		{
			profileProgressReady = false;
			// arm the tick retry: a caller that did not pre-check the key
			// (e.g. RuneLite's own ProfileChanged during a config-profile
			// switch) must not strand the pipeline with both flags down -
			// that silently disables ALL auto-completion until relog
			profileReloadPending = client.getGameState() == GameState.LOGGED_IN;
			return;
		}

		Set<String> beforeCompleted = completedSnapshot();
		Set<String> beforeSkipped = skippedSnapshot();
		Map<String, String> beforePhases = new HashMap<>(activePhaseIds);
		String beforeProfile = loadedProgressProfileKey;

		loadCompletedSteps();
		loadSkippedSteps();
		if (!stepExecutionPlans.isEmpty())
		{
			loadExecutionPhaseCursors(stepExecutionPlans);
		}

		Set<String> afterCompleted = completedSnapshot();
		Set<String> afterSkipped = skippedSnapshot();
		Map<String, String> afterPhases = new HashMap<>(activePhaseIds);
		boolean profileChanged = !java.util.Objects.equals(beforeProfile, profileKey);
		boolean modelChanged = profileChanged
			|| !beforeCompleted.equals(afterCompleted)
			|| !beforeSkipped.equals(afterSkipped)
			|| !beforePhases.equals(afterPhases);

		loadedProgressProfileKey = profileKey;
		profileReloadPending = false;
		profileReloadAttempts = 0;

		log.info("Guide Overlay: progress store loaded via {} (profile present={}) completed {} -> {}, "
			+ "skipped {} -> {}, active phases {} -> {}", source, profileKey != null,
			beforeCompleted.size(), afterCompleted.size(), beforeSkipped.size(),
			afterSkipped.size(), beforePhases.size(), afterPhases.size());

		if (!modelChanged)
		{
			profileProgressReady = true;
			return;
		}
		// Keep automatic and manual progress mutations gated until the client-thread
		// frontier/target state below has been reset to match the newly loaded set.
		profileProgressReady = false;

		if (profileChanged)
		{
			lastRsProfileKey = profileKey;
			autoSuppressed.clear();
			rewindArmPending.clear();
			customLocationStore.invalidate();
			runAsync(() ->
			{
				if (active)
				{
					rebuildLocationPlans();
				}
			});
		}

		// Every one of these fields is derived from the active progress frontier.
		// A pin created while shared/offline progress was active must not survive
		// the switch to character progress.
		hudItemsKey = null;
		lastPrefetchKey = null;
		routeDirty = true;
		activeBankDirty = true;
		stepHighlightsDirty = true;
		sectionNoticeResetPending = true;
		runOnClientThread(() ->
		{
			pinnedStepKey = null;
			pinOrigin = PinOrigin.NONE;
			targetName = null;
			targetNpc = null;
			nextStepKey = null;
			nextStepPoint = null;
			waypointArrivalTracker.reset();
			clearArrowOnClientThread();
			clearFarTarget();
			pathfinder.clear();
			profileProgressReady = true;
			runOnSwing(() ->
			{
				if (panel != null)
				{
					panel.refreshFromModel();
					panel.onPinChanged(null);
				}
			});
		});
	}

	/**
	 * ConfigManager only discovers existing RS profiles during account/world
	 * changes. A brand-new account has no key until the first profile-scoped
	 * write, so seed the real progress key rather than polling forever.
	 */
	private void ensureProgressProfileExists()
	{
		if (!config.perCharacterProgress() || configManager.getRSProfileKey() != null
			|| client.getAccountHash() == -1L)
		{
			return;
		}
		String seed = configManager.getConfiguration(HcimGuideConfig.GROUP, progressKey());
		if (seed == null && GuideRegistry.BUILTIN_ID.equals(currentGuideId))
		{
			seed = configManager.getConfiguration(HcimGuideConfig.GROUP,
				HcimGuideConfig.COMPLETED_STEPS_KEY);
		}
		if (seed == null)
		{
			seed = "[]";
		}
		log.info("Guide Overlay: creating/seeding RuneScape progress profile");
		configManager.setRSProfileConfiguration(HcimGuideConfig.GROUP, progressKey(), seed);
		if (configManager.getRSProfileKey() != null)
		{
			reloadProgressStore("profile creation fallback");
		}
	}

	private void retryProfileReload()
	{
		if (!profileReloadPending || !config.perCharacterProgress())
		{
			return;
		}
		if (configManager.getRSProfileKey() != null)
		{
			reloadProgressStore("login fallback");
			return;
		}
		profileReloadAttempts++;
		// >= not ==: ensureProgressProfileExists early-returns while the
		// account hash is still -1, which routinely outlasts tick 3 on a
		// fresh login. A one-shot attempt then never fired again, leaving the
		// whole session unable to save progress. The method is idempotent, so
		// retrying every tick past the threshold is safe.
		if (profileReloadAttempts >= PROFILE_CREATE_ATTEMPT_TICK)
		{
			ensureProgressProfileExists();
		}
		if (profileReloadAttempts == PROFILE_RELOAD_WARNING_TICK)
		{
			// deliberately NOT logging the account hash: it is a stable per-account
			// identifier and logs get pasted into bug reports and Discord. The
			// attempt count is all this warning needs.
			log.warn("Guide Overlay: still waiting for RuneScape profile after {} game ticks",
				profileReloadAttempts);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// RuneScapeProfileChanged is authoritative, but it may have fired
			// before a late plugin enable. Check the current key immediately and
			// keep a bounded/lightweight fallback for login ordering.
			profileReloadPending = config.perCharacterProgress();
			profileProgressReady = !config.perCharacterProgress();
			profileReloadAttempts = 0;
			if (configManager.getRSProfileKey() != null)
			{
				reloadProgressStore("LOGGED_IN immediate check");
			}
		}
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
				runOnSwing(() -> panel.setTargetStatus(name, false));
			}
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			clearFarTarget();
			nextStepPoint = null; // no stale compass fallback across sessions
			// game ticks stop while logged out, so the per-tick hand-off can't
			// take the drawn path or its compass snapshot down - clear both
			// here or the next character's first frames show the old target
			pathfinder.clear();
			runAsync(locationStore::saveIfDirty);
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
			|| "fullDbPrompted".equals(key)
			// the plugin's own bulk-written persistence keys: waypoint
			// navigation, phase advancement, bank-stock tracking and one-shot
			// prompts write these routinely - don't re-run the generic config
			// reaction for our own writes
			|| "customLocationsV1".equals(key)
			|| "customLocationsV1Backup".equals(key)
			|| "waypointIndexesV1".equals(key)
			|| key.startsWith("executionPhases")
			|| "teleportBankStock".equals(key)
			|| "importPrompted".equals(key))
		{
			return;
		}

		// Native hint-arrow state is shared by the client; clear it whenever
		// the optional native fallback is disabled. The custom colored arrow
		// is rendered independently by TargetOverlay. Re-asserting honors the
		// same gates as target tracking, or a config change would flash an
		// arrow the next tick immediately takes down again.
		runOnClientThread(() ->
		{
			NPC npc = targetNpc;
			if (!config.nativeHintArrow())
			{
				clearArrowOnClientThread();
			}
			else if (npc != null && !isLocationSuppressed(guidedStepKey())
				&& allowHintGuidance(npc.getWorldLocation()))
			{
				client.setHintArrow(npc);
				hintArrowSet = true;
			}
		});

		if ("overlayFontStyle".equals(key) || "overlayFontWeight".equals(key)
			|| "overlayFontSize".equals(key) || "customOverlayFontFamily".equals(key))
		{
			// Release the old native Font objects immediately; the next render
			// resolves only the newly selected combination.
			OverlayFonts.clear();
		}

		// rebuild rows so UI-affecting toggles apply without a guide refresh
		if ("showItemGrids".equals(key) || "dimCompletedSteps".equals(key)
			|| "autoCollapseCompleted".equals(key) || "panelFontSize".equals(key)
			|| "panelFontFamily".equals(key) || "panelFontWeight".equals(key)
			|| "customPanelFontFamily".equals(key)
			|| "itemPresenceBorders".equals(key) || "colorTransportSteps".equals(key)
			|| "transportStepColor".equals(key) || "colorDangerSteps".equals(key)
			|| "dangerStepColor".equals(key) || "colorPreparationSteps".equals(key)
			|| "preparationStepColor".equals(key))
		{
			runOnSwing(() -> panel.onConfigChanged());
		}

		if ("highlightStepNpcs".equals(key) || "highlightGroundItems".equals(key)
			|| "highlightStepObjects".equals(key))
		{
			stepHighlightsDirty = true;
		}

		// switching progress scope re-routes reads to the other store
		if ("perCharacterProgress".equals(key))
		{
			profileReloadPending = config.perCharacterProgress()
				&& client.getGameState() == GameState.LOGGED_IN;
			profileProgressReady = !config.perCharacterProgress();
			reloadProgressStore("perCharacterProgress config change");
			autoSuppressed.clear();
			rewindArmPending.clear();
		}

		// take the world map marker down immediately when its toggle turns off
		if ("showWorldMapMarker".equals(key) && !config.showWorldMapMarker())
		{
			runOnClientThread(this::removeMapMarker);
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
				runOnClientThread(pathfinder::clear);
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

	/**
	 * Run one tick subsystem in isolation.
	 *
	 * <p>{@link #onGameTick} drives arrival detection, dialogue guidance, item
	 * presence and completion evaluation in sequence. An exception in any of
	 * them used to abandon the whole tick, so a single fault stopped everything
	 * downstream from ever running and looked like "nothing auto-completes".
	 * Each part now fails alone and says which one it was.</p>
	 */
	private void tickStep(String what, Runnable part)
	{
		try
		{
			part.run();
		}
		catch (Exception e)
		{
			if (tickFailuresLogged.add(what))
			{
				log.warn("Guide Overlay: tick step '{}' failed and was skipped; "
					+ "other features continue", what, e);
			}
		}
	}

	/** Names already logged, so a per-tick fault does not spam the log. */
	private final java.util.Set<String> tickFailuresLogged =
		java.util.concurrent.ConcurrentHashMap.newKeySet();

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		++tickCounter;
		if (profileReloadPending)
		{
			tickStep("profile-progress-reload", this::retryProfileReload);
		}
		tickStep("snooze", this::updateSnoozeExpiry);
		tickStep("guidance-band", this::updateGuidanceDistanceBand);
		tickStep("suppression", this::updateSuppressionLeaveState);
		tickStep("waypoint-arrival", this::updateWaypointArrival);
		tickStep("target-tracking", this::updateTargetTracking);
		tickStep("step-highlights", () ->
		{
			// highlights must swap in the SAME tick the objective changes - a
			// stale door outline surviving into the next step reads as random
			String key = guidedStepKey();
			String objectiveNow = key == null ? null : key + '|' + activePhaseIds.get(key);
			boolean objectiveChanged = !java.util.Objects.equals(objectiveNow, lastHighlightObjective);
			lastHighlightObjective = objectiveNow;
			updateStepHighlights(objectiveChanged
				|| tickCounter % HIGHLIGHT_SAFETY_REFRESH_TICKS == 0);
		});
		// "nearest matching object" tracks the player, so reselect every tick
		// (tiny loop over the whitelisted scene objects) - or a door behind
		// you would stay lit for up to 12 seconds after you walk past it
		tickStep("object-nearest", this::reselectNearestObjects);
		// every tick so the drawn path reacts to a completed step within one
		// game tick - it's a few cached-field reads and a deduped message
		tickStep("path-handoff", this::updatePathHandoff);
		// cheap (cached bank + key compare); resolves off-thread on step change
		tickStep("hud-items", this::updateHudItems);
		// one-shot: floating arrows that were never positioned start mid-screen
		tickStep("arrow-placement", this::ensureFloatingArrowsFindable);
		// trip-ready state + section-complete confirmation (both cheap walks
		// over the cached active bank; sounds/messages only on transitions)
		tickStep("trip-ready", this::updateTripReady);
		tickStep("section-notice", this::updateSectionCompleteNotice);
		// dialogue-choice guidance: which option to outline right now
		tickStep("dialog-highlight", this::updateDialogHighlight);
		// summary snapshot for the EDT/overlays (client-thread state inside)
		tickStep("summary-snapshot", () ->
		{
			activeLocationSummarySnapshot = getActiveLocationSummary();
			waypointStatusSnapshot = getWaypointStatus();
			StepLocationHint activeHint = resolveStepLocation(guidedStepKey());
			activeStopIsTransport = activeHint != null && activeHint.isTransport();
			StepLocationPlan waypointPlan = getStepPlan(guidedStepKey());
			waypointCounterSnapshot = waypointPlan == null || !waypointPlan.hasWaypoints()
				? null
				: (activeWaypointIndex(guidedStepKey(), waypointPlan) + 1) + "/" + waypointPlan.size();
		});

		if (tickCounter % EVAL_INTERVAL_TICKS == 0)
		{
			// each in its own tickStep, like the per-tick subsystems above: a
			// throw in one (e.g. a corrupt condition) must log its own name
			// and leave the others running, not freeze the bank tag, routing
			// and icon pipeline every evaluation tick
			tickStep("auto-completion", this::evaluateAutoCompletion);
			tickStep("bank-tag", this::syncBankTag);
			tickStep("routing", this::updateRouting);
			tickStep("icon-prefetch", this::prefetchUpcomingIcons);
			// names still awaiting the full-database scan: re-kick it. This is
			// what guarantees trip-ready's PENDING state converges - a scan
			// that was busy when a name arrived, or aborted (item cache not
			// loaded), leaves pendingScan non-empty and nothing else retries.
			// Self-deduping: no-ops when nothing is pending or a scan runs.
			tickStep("icon-scan-rekick", () ->
			{
				if (iconResolver.hasPendingScan())
				{
					iconResolver.scanFullDatabase(() ->
						runOnSwing(() -> panel.reresolveIcons()));
				}
			});
		}
	}

	private void updateSnoozeExpiry()
	{
		if (locationSnoozeUntilTick != 0 && tickCounter >= locationSnoozeUntilTick)
		{
			locationSnoozeUntilTick = 0;
			routeDirty = true;
		}
	}

	/** A hide is step-scoped: the guided step moving on clears it. */
	private void updateSuppressionLeaveState()
	{
		if (locationSuppression.clearWhenGuidedStepChanged(guidedStepKey()))
		{
			routeDirty = true;
		}
	}

	/**
	 * Advance ordered waypoints and complete explicit travel-only steps at their
	 * final authored destination. Arrival is deliberately not treated as proof
	 * for compound actions, entity interactions, inherited locations, or steps
	 * with a separate item/quest/skill condition.
	 */
	private void updateWaypointArrival()
	{
		boolean canAdvance = config.autoAdvanceWaypoints();
		// "Complete travel steps on arrival" is its own setting - not nested
		// under the item/quest/skill auto-completion master switch
		boolean canComplete = config.autoCompleteOnArrival();
		if ((!canAdvance && !canComplete) || client.getLocalPlayer() == null
			|| client.getGameState() != GameState.LOGGED_IN
			|| (config.perCharacterProgress() && !profileProgressReady))
		{
			waypointArrivalTracker.reset();
			return;
		}

		String key = guidedStepKey();
		StepLocationPlan plan = getStepPlan(key);
		if (key == null || plan == null || !plan.hasWaypoints() || isStepDone(key))
		{
			waypointArrivalTracker.reset();
			return;
		}
		// Deliberately NOT in the gate above: hiding/snoozing the location
		// guide is a VISUAL choice and a manual untick is a COMPLETION veto -
		// neither may freeze waypoint/phase progression while the player
		// walks the route. The untick only blocks the final auto-complete.
		boolean completionSuppressed = autoSuppressed.contains(key);

		int index = activeWaypointIndex(key, plan);
		StepLocationHint waypoint = plan.get(index);
		WorldPoint me = client.getLocalPlayer().getWorldLocation();
		if (!waypointArrivalTracker.update(key, index, waypoint, me))
		{
			return;
		}

		if (index < plan.size() - 1)
		{
			if (canAdvance)
			{
				navigateWaypoint(1, true);
			}
			return;
		}

		GuideStep step = stepsByKey.get(key);
		StepExecutionPlan execution = activeExecutionPlan(key);
		StepExecutionPhase phase = activeExecutionPhase(key);
		if (execution != null && phase != null)
		{
			if (!phase.isTravel())
			{
				// A satisfied PREPARATION phase must not leave arrival inert:
				// the cursor sits on phase 0 until the item list proves, and
				// evaluateAutoCompletion (the other advancer) only covers the
				// ACTIVE bank every 4 ticks - a pinned step outside it would
				// otherwise never advance at all. Arrival still proves nothing
				// for TASK phases or for unmet preparation.
				if (phase.getKind() == StepPhaseParser.Kind.PREPARATION
					&& phase.getCondition() != null && conditionMet(phase.getCondition())
					&& (canAdvance || canComplete)
					&& activeExecutionPhaseIndex(key, execution) < execution.size() - 1)
				{
					advanceExecutionPhase(key, "arrival-prep-satisfied");
				}
				return; // a task may have a destination, but arrival is not proof
			}
			int phaseIndex = activeExecutionPhaseIndex(key, execution);
			if (phaseIndex < execution.size() - 1)
			{
				if (canAdvance || canComplete)
				{
					advanceExecutionPhase(key, "arrival");
				}
				return;
			}
			// A guidance-only plan may TARGET but never complete. Its final phase
			// can be pure travel text ("return to Thurgo") while the row also
			// required an earlier task, so the phase text alone is not proof.
			if (!canComplete || completionSuppressed
				|| execution.getMode() == StepPhaseParser.Mode.GUIDANCE_ONLY)
			{
				log.debug("Guide Overlay: arrival completion refused: {}",
					!canComplete ? "DISABLED"
						: completionSuppressed ? "MANUALLY_SUPPRESSED" : "GUIDANCE_ONLY");
				return;
			}
			ArrivalCompletionPolicy.Decision phased = ArrivalCompletionPolicy.decide(
				phase.getText(), plan, phase.getCondition() != null,
				phase.getEntityTarget() != null, structuralParentSteps.contains(key));
			if (phased == ArrivalCompletionPolicy.Decision.ELIGIBLE)
			{
				completeStepFromArrival(step);
			}
			else
			{
				// decision name only - never imported step text
				log.debug("Guide Overlay: arrival completion refused: {}", phased);
			}
			return;
		}

		if (!canComplete || completionSuppressed || step == null)
		{
			// same diagnosability as the phased path: name the refusal
			log.debug("Guide Overlay: arrival completion refused: {}",
				!canComplete ? "DISABLED"
					: completionSuppressed ? "MANUALLY_SUPPRESSED" : "NO_STEP");
			return;
		}
		ArrivalCompletionPolicy.Decision decision = ArrivalCompletionPolicy.decide(
			step.getText(), plan, conditions.containsKey(key),
			stepTargets.containsKey(key), structuralParentSteps.contains(key));
		if (decision == ArrivalCompletionPolicy.Decision.ELIGIBLE)
		{
			completeStepFromArrival(step);
		}
		else
		{
			// decision name only - never imported step text
			log.debug("Guide Overlay: arrival completion refused: {}", decision);
		}
	}

	/** Complete one travel step and refresh the same UI/guidance state as other auto-completion. */
	private void completeStepFromArrival(GuideStep step)
	{
		if (step == null || isStepDone(step.getKey()) || autoSuppressed.contains(step.getKey())
			|| !canMutateProgressStore())
		{
			return;
		}
		setCompleted(step.getKey(), true);
		if (!isCompleted(step.getKey()))
		{
			return;
		}
		// deliberately NOT self-suppressed: isStepDone gates re-firing while
		// completed, a checkbox untick vetoes, and a navigation rewind is
		// SUPPOSED to allow completing again on a fresh confirmed arrival
		// The player may have imported this text from a private guide; do not
		// copy the key or row text into persistent logs.
		log.debug("Guide Overlay: completed a travel step on final arrival");
		if (config.notifyAutoComplete())
		{
			String msg = "Guide Overlay: completed travel step \""
				+ Text.removeTags(shorten(step.getText())) + "\"";
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
		}
		runOnSwing(() ->
		{
			if (active && panel != null)
			{
				panel.refreshFromModel();
			}
		});
	}

	private static int tileDistance(WorldPoint a, WorldPoint b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
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
		if (objective == null || !allowShortestPathGuidance(objective))
		{
			pathfinder.clear();
			return;
		}
		// A CHANGED objective (new guided step or phase) must always retarget,
		// even when the new destination sits inside the moving-NPC deadband of
		// the old one - two adjacent guide objectives must never share a stale
		// path or compass for the keepalive interval.
		String guidedKey = guidedStepKey();
		String objectiveKey = guidedKey == null ? null
			: guidedKey + '|' + activePhaseIds.get(guidedKey);
		if (!java.util.Objects.equals(objectiveKey, lastPathObjectiveKey))
		{
			lastPathObjectiveKey = objectiveKey;
			pathfinder.forceResend();
		}
		// Shortest Path trims the walked tail only when it recomputes, and it
		// only recomputes on receiving a target. Resend after the player has
		// moved far enough that the head of the path is visibly stale.
		int refreshTiles = config.pathRefreshTiles();
		if (refreshTiles > 0)
		{
			WorldPoint me = client.getLocalPlayer() == null
				? null : client.getLocalPlayer().getWorldLocation();
			if (me != null)
			{
				if (lastPathRefreshFrom == null
					|| me.getPlane() != lastPathRefreshFrom.getPlane()
					|| Math.max(Math.abs(me.getX() - lastPathRefreshFrom.getX()),
						Math.abs(me.getY() - lastPathRefreshFrom.getY())) >= refreshTiles)
				{
					lastPathRefreshFrom = me;
					pathfinder.forceResend();
				}
			}
		}
		pathfinder.setTarget(objective, getTargetNpc() != null);
	}

	/** Where the player was when the drawn path was last refreshed. */
	private WorldPoint lastPathRefreshFrom;

	/** Step|phase the hand-off last targeted; a change forces a resend. */
	private String lastPathObjectiveKey;

	// ------------------------------------------------------------------ routing & teleports

	/** HUD accessor; prebuilt string, null when there is nothing to suggest. */
	String getRouteSuggestion()
	{
		return routeSuggestion;
	}

	/**
	 * The point the player is currently heading to: the guided step's target
	 * (live NPC position when in scene, last known location otherwise).
	 * Derived from the same authoritative guidance key as every overlay, so
	 * the drawn path and route suggestion always agree with the arrows.
	 * Client thread.
	 */
	private WorldPoint currentObjective()
	{
		String key = guidedStepKey();
		if (key == null || isLocationSuppressed(key))
		{
			return null;
		}
		StepLocationHint precomputed = activeWaypoint(key);
		if (precomputed != null && precomputed.isPreferredOverEntity())
		{
			return farTarget != null ? farTarget : precomputed.getPoint();
		}
		NPC npc = targetNpc;
		if (npc != null)
		{
			return npc.getWorldLocation();
		}
		if (farTarget != null)
		{
			return farTarget;
		}
		// unfiltered like the compass: a coarse low-confidence destination
		// still beats no route at all; precise markers keep the filter
		StepLocationHint known = resolveStepLocationAnyConfidence(key);
		return known == null ? null : known.getPoint();
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
		runAsync(() ->
		{
			if (active)
			{
				iconResolver.resolve(reqsCopy);
				// names the price search missed land in pendingScan; the
				// periodic re-kick on the game tick picks them up from there
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
	/**
	 * A gating step blocks progress until something is done in the world, so a
	 * bank trip cannot be prepared past it. Training a skill to a level,
	 * reaching a level, completing or continuing a quest, or killing a boss all
	 * gate. Item withdrawal/collection steps are NOT gates - those are exactly
	 * what trip-prep should look ahead to.
	 */
	private static final java.util.regex.Pattern GATING_STEP =
		java.util.regex.Pattern.compile(
			"(?i)\\b(?:train(?:ing)?|get(?:ting)? (?:to )?(?:level|lvl|\\d)|"
			+ "reach(?:ing)? (?:level|lvl|\\d)|until (?:level|lvl|\\d)|"
			+ "level up|achieve (?:level|lvl)|"
			+ "(?:start|begin|complete|completing|continue|continuing|finish|finishing|"
			+ "progress|do) (?:the )?[A-Z][^.\\n]{2,}|"
			+ "kill(?:ing)? (?:the )?[A-Z]|defeat(?:ing)? (?:the )?[A-Z]|"
			+ "tithe farm|pest control|wintertodt|tempoross|barbarian assault|"
			+ "unlock(?:ing)?)\\b");

	/** True when the step blocks trip-prep lookahead (see {@link #GATING_STEP}). */
	private static boolean isGatingStep(String stepText)
	{
		return stepText != null && GATING_STEP.matcher(stepText).find();
	}

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
		// Trip-prep lookahead with a gating stop. A single bank trip can hold
		// items for several upcoming steps, so we tag the current step plus the
		// next few item-bearing steps. But we STOP at a gating step - one that
		// blocks progress until an action is done in the world (train a skill to
		// a level, complete a quest, kill a boss). You cannot prepare past that
		// point in one trip, so tagging items from beyond it (e.g. seedbox and
		// autoweed for a later farming objective while still on a Tithe Farm
		// level-gate step) is wrong. The current step's own items always tag,
		// even when the current step itself is a gate.
		final int maxItemSteps = 12;
		int itemSteps = 0;
		boolean atCurrentStep = true;
		List<ItemReq> items = new ArrayList<>();
		StringBuilder signature = new StringBuilder(active.getId())
			.append("@r").append(iconResolver.revision());
		for (GuideStep step : active.getSteps())
		{
			if (isStepDone(step.getKey()))
			{
				continue;
			}
			// a gating step reached AFTER the current one ends the lookahead:
			// nothing beyond a gate belongs to this trip
			boolean gate = isGatingStep(step.getText());
			if (!atCurrentStep && gate)
			{
				break;
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
			// if the CURRENT step is itself a gate, its own items still tag but
			// nothing after it does: the player cannot prepare past the gate.
			// This is the case the previous version missed, which let a later
			// step's items (seedbox, autoweed) show while still on Tithe Farm.
			if (gate)
			{
				break;
			}
			atCurrentStep = false;
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
		if (event.getGroupId() == InterfaceID.WORLDMAP)
		{
			centerMapOnTarget();
		}
	}

	/**
	 * Start the world map on the current destination when it is OPENED.
	 *
	 * <p>Deliberately only on open. Recentring whenever the target changes would
	 * move the map under the player while they were panning it, which is the
	 * plugin acting without being asked.</p>
	 */
	private void centerMapOnTarget()
	{
		if (!config.centerMapOnOpen() || !config.showWorldMapMarker())
		{
			return;
		}
		final WorldPoint target = markerPoint;
		if (target == null)
		{
			return;
		}
		// the map widget is not laid out yet on the load event
		runOnClientThread(() ->
		{
			net.runelite.api.worldmap.WorldMap map = client.getWorldMap();
			if (map != null)
			{
				map.setWorldMapPositionTarget(target);
			}
		});
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
		// the same authoritative guidance key as every other consumer - the
		// dialogue outline must never track a different step than the arrows
		String key = guidedStepKey();
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
			// without this, the last guided step's words keep selecting the
			// nearest door in every scene AFTER the guide model is gone
			currentStepObjectWords = Collections.emptySet();
			lastHighlightObjective = null;
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
		GuideBank active = findActiveBank(guide);
		if (active != null && npcsWanted)
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
				String t = stepTargets.get(step.getKey());
				if (t != null)
				{
					wantedNorm.add(Names.normalize(t));
				}
			}
		}

		// Ground items and scene objects highlight for the CURRENT step ONLY
		// (pinned, else the first unchecked) - narrowed further to the active
		// execution phase when it has its own text/condition. Collecting them
		// across the whole bank lit doors and stairs for steps half an hour
		// away, which read as random and never went away until that distant
		// step was ticked.
		List<ItemReq> itemReqs = new ArrayList<>();
		Set<String> wantedObjects = new HashSet<>();
		String currentKey = guidedStepKey();
		GuideStep currentStep = currentKey == null ? null : stepsByKey.get(currentKey);
		if (currentStep != null)
		{
			StepExecutionPhase phase = activeExecutionPhase(currentKey);
			if (itemsWanted)
			{
				StepCondition c = phase != null && phase.getCondition() != null
					? phase.getCondition() : conditions.get(currentKey);
				if (c != null && c.getType() == StepCondition.Type.ITEMS_IN_INVENTORY)
				{
					itemReqs.addAll(c.getItems());
				}
			}
			if (objectsWanted)
			{
				String sourceText = phase != null && phase.getText() != null
					? phase.getText() : currentStep.getText();
				wantedObjects.addAll(TargetExtractor.objectWordsIn(sourceText));
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

		currentStepObjectWords = wantedObjects;
		reselectNearestObjects();
	}

	/** Object words mentioned by the current step; cached between rebuilds. */
	private Set<String> currentStepObjectWords = Collections.emptySet();
	/** step key + phase id last used for highlights; change forces a same-tick rebuild. */
	private String lastHighlightObjective;

	/**
	 * Only the APPLICABLE object: the step means the door you're standing
	 * near, not every door in the scene - so per mentioned type, highlight
	 * the single nearest same-plane instance. Runs every tick (the player
	 * moves, so "nearest" moves); the loop covers only the few dozen
	 * whitelisted objects the scene actually contains.
	 */
	private void reselectNearestObjects()
	{
		Set<String> wantedObjects = currentStepObjectWords;
		if (wantedObjects.isEmpty() || client.getLocalPlayer() == null)
		{
			if (!objectHighlights.isEmpty())
			{
				objectHighlights = new ArrayList<>();
			}
			return;
		}
		WorldPoint me = client.getLocalPlayer().getWorldLocation();
		Map<String, net.runelite.api.TileObject> nearest = new HashMap<>();
		Map<String, Integer> nearestDist = new HashMap<>();
		for (Map.Entry<net.runelite.api.TileObject, String> e : sceneObjects.entrySet())
		{
			String word = e.getValue();
			if (!wantedObjects.contains(word))
			{
				continue;
			}
			net.runelite.api.TileObject obj = e.getKey();
			WorldPoint loc = obj.getWorldLocation();
			if (loc == null || loc.getPlane() != me.getPlane())
			{
				continue;
			}
			int d = me.distanceTo2D(loc);
			Integer best = nearestDist.get(word);
			if (best == null || d < best)
			{
				nearest.put(word, obj);
				nearestDist.put(word, d);
			}
		}
		objectHighlights = new ArrayList<>(nearest.values());
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
		if (guide == null || !config.autoComplete()
			|| (config.perCharacterProgress() && client.getGameState() == GameState.LOGGED_IN
				&& !profileProgressReady))
		{
			return;
		}
		if (!canMutateProgressStore())
		{
			return;
		}

		GuideBank activeBank = findActiveBank(guide);
		if (activeBank == null)
		{
			return;
		}

		List<GuideStep> justCompleted = new ArrayList<>();
		List<GuideStep> toEvaluate = new ArrayList<>(activeBank.getSteps());
		// A PINNED step may live outside the active bank; nothing else can
		// advance its preparation/condition phases, so evaluate it too - or
		// its arrival pipeline stays inert by construction.
		String pinned = pinnedStepKey;
		if (pinned != null)
		{
			GuideStep pinnedStep = stepsByKey.get(pinned);
			if (pinnedStep != null && !toEvaluate.contains(pinnedStep))
			{
				toEvaluate.add(pinnedStep);
			}
		}
		for (GuideStep step : toEvaluate)
		{
			if (isStepDone(step.getKey()) || autoSuppressed.contains(step.getKey()))
			{
				continue;
			}
			StepExecutionPlan execution = activeExecutionPlan(step.getKey());
			StepCondition cond = activeStepCondition(step.getKey());
			boolean met = cond != null && conditionMet(cond);
			if (rewindArmPending.contains(step.getKey()))
			{
				if (cond != null && met)
				{
					// level-triggered condition still true after a rewind:
					// stay dormant or the step snaps straight back
					continue;
				}
				// condition observed unmet (or none to gate): edge re-armed
				rewindArmPending.remove(step.getKey());
			}
			if (met)
			{
				if (execution != null
					&& activeExecutionPhaseIndex(step.getKey(), execution) < execution.size() - 1)
				{
					advanceExecutionPhase(step.getKey(), "condition");
					continue;
				}
				// not self-suppressed: a checkbox untick vetoes, a navigation
				// rewind re-arms on the condition's next false edge - and
				// while completed, the isStepDone gate above prevents re-fire
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

		// auto-advance: pin the next unchecked step with any known destination.
		// An AUTOMATIC pin - it fills guidance gaps but never outranks the
		// current checklist step, and navigation clears it freely.
		String advanceTarget = null;
		if (config.autoTrackNext() && pinOrigin != PinOrigin.USER)
		{
			GuideStep next = findNextTrackableStep(guide);
			if (next != null && !next.getKey().equals(pinnedStepKey))
			{
				pinnedStepKey = next.getKey();
				pinOrigin = PinOrigin.AUTOMATIC;
				targetName = activeStepTarget(next.getKey());
				advanceTarget = getStepTarget(next.getKey());
			}
		}

		final String finalTarget = advanceTarget;
		runOnSwing(() ->
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
					if (!isStepDone(step.getKey()) && isStepTrackable(step.getKey()))
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
					if (snap.countOf(req) < req.getCompletionQuantity())
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

	private boolean isStepTrackable(String stepKey)
	{
		StepLocationPlan plan = getStepPlan(stepKey);
		return stepKey != null && (activeStepTarget(stepKey) != null
			|| (plan != null && plan.hasWaypoints()));
	}

	private StepLocationPlan getStepPlan(String stepKey)
	{
		if (stepKey == null)
		{
			return null;
		}
		StepLocationPlan parent = stepLocationPlans.get(stepKey);
		if (hasManualOrAuthoredWaypoint(parent))
		{
			return parent;
		}
		StepExecutionPhase phase = activeExecutionPhase(stepKey);
		if (phase == null)
		{
			return parent;
		}
		// same rule as the target: a phase without its own destination falls back
		// to the parent row's rather than leaving the row unguided. An EMPTY
		// (non-null, zero-waypoint) phase plan counts as "without its own":
		// letting it through shadowed the parent's valid location and left the
		// row with no guidance at all.
		StepLocationPlan phasePlan = phase.getLocationPlan();
		return phasePlan != null && phasePlan.hasWaypoints() ? phasePlan : parent;
	}

	private static boolean hasManualOrAuthoredWaypoint(StepLocationPlan plan)
	{
		if (plan == null)
		{
			return false;
		}
		for (StepLocationHint hint : plan.getWaypoints())
		{
			if (hint.getSource() == LocationSource.USER_PIN
				|| hint.getSource() == LocationSource.AUTHORED_WAYPOINT)
			{
				return true;
			}
		}
		return false;
	}

	private int activeWaypointIndex(String stepKey, StepLocationPlan plan)
	{
		if (stepKey == null || plan == null || !plan.hasWaypoints())
		{
			return 0;
		}
		Integer index = activeWaypointIndexes.get(stepKey);
		return Math.max(0, Math.min(index == null ? 0 : index, plan.size() - 1));
	}

	private StepLocationHint activeWaypoint(String stepKey)
	{
		StepLocationPlan plan = getStepPlan(stepKey);
		return plan == null ? null : plan.get(activeWaypointIndex(stepKey, plan));
	}

	/** Latest known active destination; live NPC coordinates are handled separately. */
	private StepLocationHint resolveStepLocation(String stepKey)
	{
		StepLocationHint hint = resolveStepLocationAnyConfidence(stepKey);
		return visibleConfidence(hint) ? hint : null;
	}

	/**
	 * Same resolution WITHOUT the low-confidence visibility filter. The
	 * compass and routing objective use this: a coarse "that way" from a
	 * low-confidence pin still beats no arrow at all, while precise markers
	 * (scene tile arrow, world map pin) keep honoring the filter.
	 */
	private StepLocationHint resolveStepLocationAnyConfidence(String stepKey)
	{
		if (stepKey == null)
		{
			return null;
		}
		StepLocationHint precomputed = activeWaypoint(stepKey);
		if (precomputed != null && precomputed.isPreferredOverEntity())
		{
			return precomputed;
		}
		String target = activeStepTarget(stepKey);
		if (target != null)
		{
			WorldPoint known = locationStore.lookup(target);
			if (known != null)
			{
				return new StepLocationHint(target, known, false, false, false,
					"entity:" + Names.normalize(target), LocationSource.STORED_ENTITY,
					LocationConfidence.HIGH, 3, 2);
			}
		}
		return precomputed;
	}

	private boolean visibleConfidence(StepLocationHint hint)
	{
		return hint != null && (!config.hideLowConfidenceLocations()
			|| hint.getConfidence() != LocationConfidence.LOW);
	}

	/**
	 * THE authoritative guidance key. Every guidance consumer - scene/tile
	 * arrow, NPC and object highlighting, compass, world-map marker, Shortest
	 * Path hand-off, route suggestion, HUD location counter, arrival tracking
	 * and dialogue guidance - derives its objective from this one method, so
	 * they can never disagree about which step is being guided.
	 *
	 * <p>Precedence: a USER pin is an explicit choice and rules until unpinned
	 * or navigated away from. Otherwise the CURRENT checklist step (first
	 * unchecked in the active bank) is guided whenever it has anything to
	 * point at; an AUTOMATIC pin (auto-track-next) only fills the gap when the
	 * current step itself offers no target or location, which is what lets
	 * backward navigation atomically re-target the step under review.</p>
	 */
	private String guidedStepKey()
	{
		String pinned = pinnedStepKey;
		if (pinned != null && pinOrigin == PinOrigin.USER)
		{
			return pinned;
		}
		String current = null;
		Guide guide = currentGuide;
		GuideBank activeBank = guide == null ? null : findActiveBank(guide);
		if (activeBank != null)
		{
			for (GuideStep step : activeBank.getSteps())
			{
				if (!isStepDone(step.getKey()))
				{
					current = step.getKey();
					break;
				}
			}
		}
		if (current == null)
		{
			return pinned;
		}
		if (pinned != null && !pinned.equals(current)
			&& !isStepDone(pinned) && !isStepTrackable(current))
		{
			// the current step has nothing to point at - the automatic pin
			// (the next trackable step) keeps an arrow up until it does
			return pinned;
		}
		return current;
	}

	boolean hasLocationForGuidedStep()
	{
		return isStepTrackable(guidedStepKey());
	}

	String getWaypointStatus()
	{
		String key = guidedStepKey();
		String phase = getExecutionPhaseStatus(key);
		StepLocationPlan plan = getStepPlan(key);
		if (plan == null || !plan.hasWaypoints())
		{
			return phase;
		}
		int index = activeWaypointIndex(key, plan);
		String waypoint = "Waypoint " + (index + 1) + "/" + plan.size();
		return phase == null ? waypoint : phase + " · " + waypoint;
	}

	/**
	 * Per-tick snapshot of {@link #getActiveLocationSummary()}. The live
	 * computation walks client-thread state (active bank cache), so the EDT
	 * and overlay renders read this instead of recomputing.
	 */
	private volatile String activeLocationSummarySnapshot;

	/** "Waypoint 2/3" for the guided step, or null when the step has one stop. */
	private volatile String waypointStatusSnapshot;

	/**
	 * True when the ACTIVE waypoint is something to travel by rather than a
	 * place to reach. Only the active one is labelled: showing a marker on every
	 * stop made it unclear which transport you were actually meant to take.
	 */
	private volatile boolean activeStopIsTransport;

	boolean isActiveStopTransport()
	{
		return activeStopIsTransport;
	}

	String getWaypointStatusSnapshot()
	{
		return waypointStatusSnapshot;
	}

	/** Compact "2/3" for the HUD: informs that the location updates, nothing more. */
	private volatile String waypointCounterSnapshot;

	String getWaypointCounterSnapshot()
	{
		return waypointCounterSnapshot;
	}

	String getActiveLocationSummarySnapshot()
	{
		return activeLocationSummarySnapshot;
	}

	String getActiveLocationSummary()
	{
		StepLocationHint hint = resolveStepLocation(guidedStepKey());
		if (hint == null)
		{
			return null;
		}
		StringBuilder out = new StringBuilder(hint.getLabel());
		// the waypoint counter is deliberately NOT appended here: it is exposed
		// separately so the overlay can colour it, which is the only visible
		// feedback when the waypoint arrows are used
		if (config.showLocationConfidence())
		{
			out.append(" · ").append(hint.getSource().displayName())
				.append(" · ").append(hint.getConfidence().displayName());
		}
		return out.toString();
	}

	boolean isLocationGuideHiddenForGuidedStep()
	{
		return isLocationSuppressed(guidedStepKey());
	}

	/** Hide/show the current semantic destination, not merely the current step key. */
	void toggleLocationGuideForCurrentStep()
	{
		runOnClientThread(this::toggleLocationGuideOnClientThread);
	}

	private void toggleLocationGuideOnClientThread()
	{
		String key = guidedStepKey();
		StepLocationHint hint = resolveStepLocation(key);
		if (hint == null)
		{
			return;
		}
		if (tickCounter < locationSnoozeUntilTick)
		{
			restoreLocationGuide();
			return;
		}
		if (isLocationSuppressed(key))
		{
			clearLocationSuppression();
		}
		else
		{
			locationSuppression.hide(key);
		}
		routeDirty = true;
		runOnClientThread(() ->
		{
			if (isLocationSuppressed(key))
			{
				clearArrowOnClientThread();
				clearFarTarget();
				nextStepPoint = null;
				nextStepKey = null;
				routeSuggestion = null;
				pathfinder.clear();
			}
		});
	}

	private void clearLocationSuppression()
	{
		locationSuppression.clear();
	}

	private boolean isLocationSuppressed(String stepKey)
	{
		if (tickCounter < locationSnoozeUntilTick)
		{
			return true;
		}
		// strictly step-scoped: only the exact step the user hid is hidden;
		// the per-tick scope check clears it once the guided step moves on
		return locationSuppression.hides(stepKey);
	}

	void snoozeLocationGuide()
	{
		locationSnoozeUntilTick = tickCounter + 500; // five minutes at 0.6 s/tick
		routeDirty = true;
		runOnClientThread(() ->
		{
			clearArrowOnClientThread();
			clearFarTarget();
			nextStepPoint = null;
			nextStepKey = null;
			routeSuggestion = null;
			pathfinder.clear();
		});
		runOnSwing(() -> panel.setStatus("Location guidance snoozed for 5 minutes"));
	}

	void restoreLocationGuide()
	{
		locationSnoozeUntilTick = 0;
		clearLocationSuppression();
		routeDirty = true;
		runOnSwing(() -> panel.setStatus("Location guidance restored"));
	}

	private void navigateWaypoint(int direction, boolean automatic)
	{
		String key = guidedStepKey();
		StepLocationPlan plan = getStepPlan(key);
		if (key == null || plan == null || plan.size() <= 1 || direction == 0)
		{
			return;
		}
		int current = activeWaypointIndex(key, plan);
		int next = Math.max(0, Math.min(plan.size() - 1, current + (direction > 0 ? 1 : -1)));
		if (next == current)
		{
			return;
		}
		activeWaypointIndexes.put(key, next);
		if (config.persistWaypointIndex())
		{
			customLocationStore.setActiveIndex(currentGuideId, key, next);
		}
		waypointArrivalTracker.reset();
		// An AUTOMATIC advance must not clear a hide: progression continues
		// silently on hidden routes (that's the whole point of decoupling
		// them), and the user's explicit choice stands until the step ends.
		if (!automatic)
		{
			clearLocationSuppression();
		}
		routeDirty = true;
		stepHighlightsDirty = true;
		targetNpc = null;
		clearArrowOnClientThread();
		clearFarTarget();
		pathfinder.clear();
		final String summary = getActiveLocationSummary();
		runOnSwing(() ->
		{
			panel.onPinChanged(summary);
			if (!automatic)
			{
				panel.setStatus("Selected " + getWaypointStatus());
			}
		});
	}

	void setCurrentTileAsCustomPin(boolean append)
	{
		runOnClientThread(() ->
		{
			String key = guidedStepKey();
			if (key == null || client.getLocalPlayer() == null)
			{
				return;
			}
			WorldPoint point = client.getLocalPlayer().getWorldLocation();
			setCustomPin(key, append, "Custom pin", point);
		});
	}

	void setWorldMapCenterAsCustomPin(boolean append)
	{
		runOnClientThread(() ->
		{
			String key = guidedStepKey();
			if (key == null || client.getWorldMap() == null)
			{
				return;
			}
			net.runelite.api.Point map = client.getWorldMap().getWorldMapPosition();
			if (map == null)
			{
				return;
			}
			// The world map exposes x/y but no selected plane. Global map pins are
			// therefore surface points; use the current-tile action for interiors.
			setCustomPin(key, append, "Custom map pin", new WorldPoint(map.getX(), map.getY(), 0));
		});
	}

	private void setCustomPin(String stepKey, boolean append, String label, WorldPoint point)
	{
		try
		{
			if (append)
			{
				customLocationStore.append(currentGuideId, stepKey, getStepPlan(stepKey), label, point);
			}
			else
			{
				customLocationStore.replace(currentGuideId, stepKey, label, point);
			}
			republishStepPlan(stepKey);
			StepLocationPlan plan = getStepPlan(stepKey);
			if (plan != null && append)
			{
				int last = plan.size() - 1;
				activeWaypointIndexes.put(stepKey, last);
				if (config.persistWaypointIndex())
				{
					customLocationStore.setActiveIndex(currentGuideId, stepKey, last);
				}
			}
			clearLocationSuppression();
			routeDirty = true;
			stepHighlightsDirty = true;
			runOnSwing(() -> panel.setStatus(append
				? "Added custom waypoint" : "Custom destination saved"));
		}
		catch (RuntimeException ex)
		{
			runOnSwing(() -> panel.setStatus("Custom pin failed: " + ex.getMessage()));
		}
	}

	void renameActiveCustomWaypoint()
	{
		String key = guidedStepKey();
		StepLocationPlan plan = getStepPlan(key);
		if (key == null || plan == null || !customLocationStore.hasPlan(currentGuideId, key))
		{
			runOnSwing(() -> panel.setStatus("This step has no custom waypoint to rename"));
			return;
		}
		int index = activeWaypointIndex(key, plan);
		String current = plan.get(index).getLabel();
		runOnSwing(() ->
		{
			String label = javax.swing.JOptionPane.showInputDialog(panel,
				"Waypoint label:", current);
			if (label == null)
			{
				return;
			}
			runOnClientThread(() ->
			{
				try
				{
					// the index was captured before the dialog blocked - a
					// reorder or auto-advance meanwhile could have moved it.
					// Re-validate by label so the rename can't hit a
					// different waypoint than the one the user was shown.
					StepLocationPlan livePlan = getStepPlan(key);
					if (livePlan == null || index >= livePlan.size()
						|| !livePlan.get(index).getLabel().equals(current))
					{
						runOnSwing(() ->
							panel.setStatus("Waypoints changed while renaming - try again"));
						return;
					}
					customLocationStore.rename(currentGuideId, key, index, label);
					republishStepPlan(key);
					runOnSwing(() -> panel.setStatus("Waypoint renamed"));
				}
				catch (RuntimeException ex)
				{
					runOnSwing(() -> panel.setStatus("Rename failed: " + ex.getMessage()));
				}
			});
		});
	}

	void moveActiveCustomWaypoint(int direction)
	{
		runOnClientThread(() -> moveActiveCustomWaypointOnClientThread(direction));
	}

	private void moveActiveCustomWaypointOnClientThread(int direction)
	{
		String key = guidedStepKey();
		StepLocationPlan plan = getStepPlan(key);
		if (key == null || plan == null || !customLocationStore.hasPlan(currentGuideId, key))
		{
			runOnSwing(() -> panel.setStatus("This step has no custom waypoint to reorder"));
			return;
		}
		try
		{
			int moved = customLocationStore.move(currentGuideId, key, activeWaypointIndex(key, plan), direction);
			republishStepPlan(key);
			activeWaypointIndexes.put(key, moved);
			clearLocationSuppression();
			routeDirty = true;
			runOnSwing(() -> panel.setStatus("Custom waypoint reordered"));
		}
		catch (RuntimeException ex)
		{
			runOnSwing(() -> panel.setStatus("Reorder failed: " + ex.getMessage()));
		}
	}

	void removeActiveCustomWaypoint()
	{
		runOnClientThread(this::removeActiveCustomWaypointOnClientThread);
	}

	private void removeActiveCustomWaypointOnClientThread()
	{
		String key = guidedStepKey();
		StepLocationPlan plan = getStepPlan(key);
		if (key == null || plan == null || !customLocationStore.hasPlan(currentGuideId, key))
		{
			runOnSwing(() -> panel.setStatus("This step has no custom waypoint to remove"));
			return;
		}
		try
		{
			int next = customLocationStore.removeWaypoint(currentGuideId, key, activeWaypointIndex(key, plan));
			republishStepPlan(key);
			activeWaypointIndexes.put(key, next);
			clearLocationSuppression();
			routeDirty = true;
			runOnSwing(() -> panel.setStatus("Custom waypoint removed"));
		}
		catch (RuntimeException ex)
		{
			runOnSwing(() -> panel.setStatus("Remove failed: " + ex.getMessage()));
		}
	}

	void clearCustomPinForCurrentStep()
	{
		runOnClientThread(this::clearCustomPinForCurrentStepOnClientThread);
	}

	private void clearCustomPinForCurrentStepOnClientThread()
	{
		String key = guidedStepKey();
		if (key == null)
		{
			return;
		}
		try
		{
			customLocationStore.clear(currentGuideId, key);
			republishStepPlan(key);
			clearLocationSuppression();
			runOnSwing(() -> panel.setStatus("Automatic destination restored"));
		}
		catch (RuntimeException ex)
		{
			runOnSwing(() -> panel.setStatus("Restore failed: " + ex.getMessage()));
		}
	}

	/** Run panel-initiated work on the plugin executor, never the EDT. */
	void runOffEdt(Runnable work)
	{
		runAsync(() ->
		{
			if (active)
			{
				work.run();
			}
		});
	}

	String exportCustomLocations()
	{
		return customLocationStore.exportGuide(currentGuideId);
	}

	/**
	 * User-confirmed escape hatch for an unreadable pin store: without it
	 * the write refusal in CustomLocationStore is a dead end (nothing else
	 * ever clears CORRUPTED/OVERSIZED). No-op on a healthy store.
	 */
	void resetDamagedPinStore()
	{
		// generation-guarded like all other async work: a disable and re-enable
		// during the reset must not let this rebuild plans or touch the panel
		// belonging to a later activation
		runAsync(() ->
		{
			if (!customLocationStore.isDamaged())
			{
				runOnSwing(() ->
					panel.setStatus("Custom pin data is healthy - nothing to reset"));
				return;
			}
			customLocationStore.resetDamagedStore();
			rebuildLocationPlans();
			runOnSwing(() ->
				panel.setStatus("Damaged custom pin data discarded"));
		});
	}

	int importCustomLocations(String json)
	{
		Set<String> valid = new HashSet<>();
		Guide guide = currentGuide;
		if (guide != null)
		{
			for (GuideEpisode episode : guide.getEpisodes())
			{
				for (GuideBank bank : episode.getBanks())
				{
					for (GuideStep step : bank.getSteps())
					{
						valid.add(step.getKey());
					}
				}
			}
		}
		int imported = customLocationStore.importJson(json, currentGuideId, valid);
		rebuildLocationPlans();
		return imported;
	}

	/** Every distinct DISPLAY requirement in the loaded guide, in guide order. */
	private java.util.List<ItemReq> allGuideRequirements(Guide guide,
		java.util.Map<String, java.util.List<ItemReq>> requirementsByStep)
	{
		java.util.Set<String> seen = new java.util.HashSet<>();
		java.util.List<ItemReq> everyReq = new java.util.ArrayList<>();
		for (GuideEpisode episode : guide.getEpisodes())
		{
			for (GuideBank bank : episode.getBanks())
			{
				for (GuideStep step : bank.getSteps())
				{
					java.util.List<ItemReq> reqs = requirementsByStep.get(step.getKey());
					if (reqs == null)
					{
						continue;
					}
					for (ItemReq req : reqs)
					{
						String key = requirementResolutionKey(req);
						if (seen.add(key))
						{
							everyReq.add(req);
						}
					}
				}
			}
		}
		return everyReq;
	}

	/** Identity relevant to icon resolution; quantity does not change the icon. */
	private static String requirementResolutionKey(ItemReq req)
	{
		if (req.isCategoryRequirement())
		{
			return "category:" + req.getCategory().name();
		}
		if (req.isAlternativeGroup())
		{
			java.util.List<String> options = new java.util.ArrayList<>();
			for (String option : req.getAlternatives())
			{
				options.add(ItemReq.normalize(option));
			}
			return "alternatives:" + String.join("|", options);
		}
		return "exact:" + ItemReq.normalize(req.getName());
	}

	/**
	 * Resolve every item in the guide up front and report when the background
	 * scan has actually finished.
	 *
	 * <p>Icon resolution is asynchronous: names that are not in the price list
	 * go to a full scan of every item definition, which runs in chunks over
	 * following ticks. An audit taken before that finishes reports a guide as
	 * cleaner than it is, so this exists to make the wait explicit and visible
	 * rather than silent.</p>
	 */
	void preloadGuideItems()
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			panel.setStatus("Load a guide first");
			return;
		}
		java.util.Map<String, java.util.List<ItemReq>> requirements = stepItems;
		java.util.List<ItemReq> all = allGuideRequirements(guide, requirements);
		panel.setStatus("Resolving " + all.size() + " unique items...");
		runAsync(() ->
		{
			iconResolver.resolve(all);
			// kick the scan directly rather than waiting for the next tick to
			// notice; the tick path still runs and is self-deduping
			runOnClientThread(() -> iconResolver.scanFullDatabase(
				() -> runOnSwing(() -> panel.reresolveIcons())));
			pollPreload(all.size(), 0, Integer.MAX_VALUE, 0);
		});
	}

	/**
	 * Report progress until the scan drains. Shows the remaining count rather
	 * than elapsed time, so a stall is visible instead of looking like slowness.
	 *
	 * @param lastRemaining remaining count at the previous poll
	 * @param stalledFor consecutive polls with no reduction
	 */
	private void pollPreload(int total, int attempt, int lastRemaining, int stalledFor)
	{
		final int remaining = iconResolver.pendingScanCount();
		if (remaining == 0)
		{
			runOnSwing(() -> panel.setStatus(
				total + " unique items ready - audit now accurate"));
			return;
		}
		final int stalls = remaining < lastRemaining ? 0 : stalledFor + 1;
		if (stalls >= 20)
		{
			// twenty seconds without the queue shrinking: something is re-adding
			// names as fast as they are condemned, so waiting longer will not help
			runOnSwing(() -> panel.setStatus(
				"Stalled: " + remaining + " left. Export anyway."));
			return;
		}
		if (attempt > 180)
		{
			runOnSwing(() -> panel.setStatus(
				"Timed out: " + remaining + " left. Export anyway."));
			return;
		}
		runOnSwing(() -> panel.setStatus("Resolving... " + remaining + " left"));
		final long generation = lifecycle.current();
		executor.schedule(() ->
		{
			if (lifecycle.isCurrent(generation))
			{
				pollPreload(total, attempt + 1, remaining, stalls);
			}
		}, 1, java.util.concurrent.TimeUnit.SECONDS);
	}

	/**
	 * One-pass item audit over the whole loaded guide. Every requirement is
	 * pushed through the resolver first, so the report reflects the live client
	 * rather than whatever happened to have been scrolled past.
	 */
	String exportItemAudit()
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			return "# No guide loaded\n";
		}
		// Ask the resolver for every name up front. Without this the report would
		// only cover requirements the user had already seen on screen.
		java.util.Map<String, java.util.List<ItemReq>> requirements = stepItems;
		iconResolver.resolve(allGuideRequirements(guide, requirements));
		return ItemAudit.toMarkdown(currentGuideId, guide, iconResolver, requirements);
	}

	String exportLocationAudit()
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			return "# No guide loaded\n";
		}
		// User-triggered audit: refresh against locations learned/imported since the
		// guide was loaded so partially resolved entity routes are not reported stale.
		rebuildLocationPlans();
		return LocationAudit.toMarkdown(currentGuideId, guide, stepLocationPlans);
	}

	private void rebuildLocationPlans()
	{
		Guide guide = currentGuide;
		if (guide == null)
		{
			return;
		}
		Map<String, StepLocationPlan> rebuilt = buildStepLocations(guide, stepTargets);
		stepLocationPlans = rebuilt;
		stepExecutionPlans = StepExecutionPlanner.buildPlans(guide, placeDirectory,
			transportResolver, locationStore::lookup, baseLocationPlans);
		loadExecutionPhaseCursors(stepExecutionPlans);
		loadWaypointIndexes(rebuilt);
		resetWaypointTrackerOnClientThread();
	}

	/**
	 * Publish ONE step's plan after a custom-pin mutation. Pin edits never
	 * change the planner's automatic output, so re-running the full planner
	 * (a client-thread freeze on big guides) is never needed: copy the
	 * published map, merge that step's custom plan (or restore its automatic
	 * one), publish. The plan cache's custom-revision watermark is advanced
	 * so the next full rebuild check stays coherent.
	 */
	private void republishStepPlan(String stepKey)
	{
		if (stepKey == null || currentGuide == null)
		{
			return;
		}
		Map<String, StepLocationPlan> copy = new HashMap<>(stepLocationPlans);
		StepLocationPlan custom = customLocationStore.getPlan(currentGuideId, stepKey);
		StepLocationPlan base = baseLocationPlans.get(stepKey);
		if (custom != null)
		{
			copy.put(stepKey, custom);
		}
		else if (base != null)
		{
			copy.put(stepKey, base);
		}
		else
		{
			copy.remove(stepKey);
		}
		Map<String, StepLocationPlan> published = Collections.unmodifiableMap(copy);
		stepLocationPlans = published;
		locationPlanCacheCustomRevision = customLocationStore.getRevision();
		// the mutated step's saved waypoint index may now be out of range
		StepLocationPlan plan = published.get(stepKey);
		Integer index = activeWaypointIndexes.get(stepKey);
		if (plan == null || (index != null && index >= plan.size()))
		{
			activeWaypointIndexes.remove(stepKey);
		}
		resetWaypointTrackerOnClientThread();
	}

	/** The arrival tracker is game-tick state: mutate it on that thread only. */
	private void resetWaypointTrackerOnClientThread()
	{
		if (client.isClientThread())
		{
			waypointArrivalTracker.reset();
		}
		else
		{
			runOnClientThread(waypointArrivalTracker::reset);
		}
	}

	/** Pin a step: track its NPC/item/place destination. Pass null to unpin. */
	void pinStep(GuideStep step)
	{
		if (step == null)
		{
			pinnedStepKey = null;
			pinOrigin = PinOrigin.NONE;
			targetName = null;
			runOnClientThread(() ->
			{
				clearArrowOnClientThread();
				targetNpc = null;
				clearFarTarget();
				// take the drawn path and its compass snapshot down NOW; the
				// next tick re-resolves for whatever step is guided after the
				// unpin/guide switch instead of leaving the old destination up
				pathfinder.clear();
			});
			return;
		}
		pinnedStepKey = step.getKey();
		pinOrigin = PinOrigin.USER;
		targetName = activeStepTarget(step.getKey());
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
		// invoke (not runOnClientThread/invokeLater): must run immediately when
		// already on the client thread to serialize with auto-advance. The
		// generation guard still applies for the queued-from-Swing case, where
		// the task could otherwise outlive a shutdown/restart.
		final long generation = lifecycle.current();
		clientThread.invoke(() ->
		{
			if (!lifecycle.isCurrent(generation))
			{
				return;
			}
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
				pinOrigin = PinOrigin.AUTOMATIC;
				targetName = activeStepTarget(next.getKey());
				final String newTarget = getStepTarget(next.getKey());
				runOnSwing(() -> panel.onPinChanged(newTarget));
			}
			else
			{
				pinStep(null);
				runOnSwing(() -> panel.onPinChanged(null));
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
					if (!isStepDone(step.getKey()) && isStepTrackable(step.getKey()))
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
		return isLocationSuppressed(guidedStepKey()) ? null : targetNpc;
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
		// the ONE guidance key: a user pin when present, otherwise the current
		// checklist step - so every located current step gets the same scene
		// arrow / far-target treatment a pinned step always got
		String stepKey = guidedStepKey();
		StepLocationHint guidedHint = stepKey == null ? null : activeWaypoint(stepKey);
		String name = guidedHint != null && guidedHint.isPreferredOverEntity()
			? null : (stepKey == null ? null : activeStepTarget(stepKey));
		if (stepKey == null)
		{
			if (targetNpc != null || hintArrowSet)
			{
				clearArrowOnClientThread();
				targetNpc = null;
			}
			farTarget = null;
			// no guided step: keep the world map useful by marking the NEXT
			// unchecked step's destination (config-gated)
			updateNextStepMarker();
			return;
		}

		boolean suppressed = isLocationSuppressed(stepKey);
		NPC found = null;
		if (name != null)
		{
			// normalize the pinned name once, not once per scene NPC
			final String nameNorm = Names.normalize(name);
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
		}

		if (found != targetNpc)
		{
			targetNpc = found;
			if (name != null)
			{
				final boolean nearby = found != null;
				runOnSwing(() -> panel.setTargetStatus(name, nearby));
			}
		}

		if (found != null)
		{
			clearFarTarget();
			if (!suppressed && config.nativeHintArrow() && allowHintGuidance(found.getWorldLocation()))
			{
				client.setHintArrow(found);
				hintArrowSet = true;
				lastArrowPoint = null;
			}
			else
			{
				clearArrowOnClientThread();
			}
			return;
		}

		StepLocationHint location = resolveStepLocationAnyConfidence(stepKey);
		if (location == null || suppressed)
		{
			clearArrowOnClientThread();
			clearFarTarget();
			// the pin can't produce guidance, so keep the next-step point
			// fresh: hasPinnedTarget() reports false in this state and the
			// compass falls back to it
			updateNextStepMarker();
			return;
		}

		WorldPoint known = location.getPoint();
		LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), known);
		if (lp != null)
		{
			// Destination is inside the loaded scene: use the native hint arrow
			// on the tile. This also covers named places without an NPC.
			// Precise markers keep the low-confidence filter (a wrong TILE
			// misleads; the coarse compass below does not).
			farTarget = null;
			updateGuidedStepMarker(stepKey, location, known, true);
			if (config.nativeHintArrow() && allowHintGuidance(known) && visibleConfidence(location))
			{
				if (!known.equals(lastArrowPoint))
				{
					client.setHintArrow(known);
					hintArrowSet = true;
					lastArrowPoint = known;
				}
			}
			else
			{
				// the gate failing must also TAKE DOWN a previously set arrow,
				// or it keeps pointing at the last destination indefinitely
				clearArrowOnClientThread();
			}
		}
		else
		{
			clearArrowOnClientThread();
			farTarget = known;
			updateGuidedStepMarker(stepKey, location, known, false);
		}
	}

	/**
	 * World-map marker for the guided step, keeping each toggle's meaning: a
	 * USER pin follows the pinned-marker switch and baseline pin behavior (no
	 * marker while the destination is in scene), while the automatically
	 * guided current step follows the next-step switch and keeps its marker
	 * up in scene too, exactly as the old next-step marker did. Precise
	 * markers also keep the low-confidence filter; the coarse compass does
	 * not.
	 */
	private void updateGuidedStepMarker(String stepKey, StepLocationHint location,
		WorldPoint known, boolean inScene)
	{
		if (stepKey.equals(pinnedStepKey) && pinOrigin == PinOrigin.USER)
		{
			if (inScene)
			{
				removeMapMarker();
			}
			else
			{
				updateMapMarker(known, location.getLabel());
			}
			return;
		}
		if (config.showNextStepOnMap() && visibleConfidence(location))
		{
			String prefix = location.isInferred() ? "next step near: " : "next step: ";
			setMarker(known, prefix + location.getLabel());
		}
		else
		{
			removeMapMarker();
		}
	}

	private void updateGuidanceDistanceBand()
	{
		if (!config.distanceAwareGuidance())
		{
			return;
		}
		WorldPoint objective = rawObjectivePoint();
		if (objective == null)
		{
			// no objective this tick: hold the band. Feeding MAX_VALUE would
			// ratchet it to FAR and stick it there for the NEXT objective.
			return;
		}
		int distance = distanceFromPlayer(objective);
		switch (guidanceBand)
		{
			case NEAR:
				if (distance > 36)
				{
					guidanceBand = GuidanceBand.MEDIUM;
				}
				break;
			case MEDIUM:
				if (distance < 28)
				{
					guidanceBand = GuidanceBand.NEAR;
				}
				else if (distance > 112)
				{
					guidanceBand = GuidanceBand.FAR;
				}
				break;
			case FAR:
			default:
				if (distance < 96)
				{
					guidanceBand = distance < 28 ? GuidanceBand.NEAR : GuidanceBand.MEDIUM;
				}
				break;
		}
	}

	WorldPoint getTargetArrowPoint()
	{
		String key = guidedStepKey();
		if (key == null || isLocationSuppressed(key))
		{
			return null;
		}
		StepLocationHint hint = resolveStepLocation(key);
		return hint == null ? null : hint.getPoint();
	}

	boolean allowSceneGuidance()
	{
		if (isLocationSuppressed(guidedStepKey()))
		{
			return false;
		}
		HcimGuideConfig.GuidanceDisplayMode mode = config.guidanceDisplayMode();
		if (mode == HcimGuideConfig.GuidanceDisplayMode.WORLD_MAP_ONLY
			|| mode == HcimGuideConfig.GuidanceDisplayMode.SHORTEST_PATH_ONLY)
		{
			return false;
		}
		if (config.preferQuestHelperMarkers() && isQuestHelperActive() && isGuidedQuestStage())
		{
			return false;
		}
		WorldPoint objective = rawObjectivePoint();
		return !config.distanceAwareGuidance() || objective == null || guidanceBand == GuidanceBand.NEAR;
	}

	boolean allowCompassGuidance(WorldPoint objective)
	{
		HcimGuideConfig.GuidanceDisplayMode mode = config.guidanceDisplayMode();
		if (mode != HcimGuideConfig.GuidanceDisplayMode.ALL)
		{
			return false;
		}
		if (!config.distanceAwareGuidance())
		{
			return true;
		}
		// MEDIUM and FAR: long-haul travel is exactly what the compass is
		// for. Only NEAR hands over to the scene highlights and hint arrow.
		return guidanceBand != GuidanceBand.NEAR;
	}

	private boolean allowHintGuidance(WorldPoint objective)
	{
		HcimGuideConfig.GuidanceDisplayMode mode = config.guidanceDisplayMode();
		if (mode == HcimGuideConfig.GuidanceDisplayMode.WORLD_MAP_ONLY
			|| mode == HcimGuideConfig.GuidanceDisplayMode.SHORTEST_PATH_ONLY)
		{
			return false;
		}
		return !config.distanceAwareGuidance() || guidanceBand == GuidanceBand.NEAR;
	}

	private boolean allowWorldMapGuidance(WorldPoint objective)
	{
		HcimGuideConfig.GuidanceDisplayMode mode = config.guidanceDisplayMode();
		if (mode == HcimGuideConfig.GuidanceDisplayMode.SHORTEST_PATH_ONLY
			|| mode == HcimGuideConfig.GuidanceDisplayMode.NEARBY_ONLY)
		{
			return false;
		}
		return !config.distanceAwareGuidance() || guidanceBand != GuidanceBand.NEAR;
	}

	private boolean allowShortestPathGuidance(WorldPoint objective)
	{
		HcimGuideConfig.GuidanceDisplayMode mode = config.guidanceDisplayMode();
		if (mode == HcimGuideConfig.GuidanceDisplayMode.WORLD_MAP_ONLY
			|| mode == HcimGuideConfig.GuidanceDisplayMode.NEARBY_ONLY)
		{
			return false;
		}
		return !config.distanceAwareGuidance() || guidanceBand != GuidanceBand.NEAR;
	}

	private int distanceFromPlayer(WorldPoint point)
	{
		return point == null || client.getLocalPlayer() == null ? Integer.MAX_VALUE
			: tileDistance(client.getLocalPlayer().getWorldLocation(), point);
	}

	private WorldPoint rawObjectivePoint()
	{
		// unfiltered: band tracking is internal distance logic, not display
		String key = guidedStepKey();
		StepLocationHint hint = resolveStepLocationAnyConfidence(key);
		return hint == null ? null : hint.getPoint();
	}

	private boolean isQuestHelperActive()
	{
		if (tickCounter - questHelperCheckedTick < 20)
		{
			return questHelperActiveCached;
		}
		questHelperCheckedTick = tickCounter;
		boolean active = false;
		for (Plugin plugin : pluginManager.getPlugins())
		{
			PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
			if (descriptor != null && "Quest Helper".equalsIgnoreCase(descriptor.name())
				&& pluginManager.isPluginActive(plugin))
			{
				active = true;
				break;
			}
		}
		questHelperActiveCached = active;
		return active;
	}

	private boolean isGuidedQuestStage()
	{
		String key = guidedStepKey();
		GuideBank bank = currentGuide == null ? null : findActiveBank(currentGuide);
		if (key == null || bank == null)
		{
			return false;
		}
		for (GuideStep step : bank.getSteps())
		{
			if (key.equals(step.getKey()))
			{
				return StepLocationPlanner.isQuestStageText(step.getText());
			}
		}
		return false;
	}

	/** Last known location of the guided step's target when it's beyond the loaded scene. Client thread. */
	WorldPoint getFarTarget()
	{
		return farTarget;
	}

	/**
	 * Exact destination represented by the latest successful Shortest Path
	 * hand-off. Safe for overlay-render reads.
	 */
	WorldPoint getShortestPathTarget()
	{
		return pathfinder.getActiveTarget();
	}

	/** The next unchecked step's known target location (or null). Client thread. */
	WorldPoint getNextStepPoint()
	{
		return nextStepPoint;
	}

	/**
	 * True while the GUIDED step is producing guidance RIGHT NOW - a live
	 * scene NPC or a resolvable destination. A guided step whose NPC is
	 * nowhere in the scene and whose name has no stored location must not
	 * block the compass fallback: it would leave the player with no arrow at
	 * all. (updateTargetTracking refreshes nextStepPoint in exactly that
	 * state, so the fallback never points at stale data.)
	 */
	boolean hasPinnedTarget()
	{
		String key = guidedStepKey();
		if (key == null)
		{
			return false;
		}
		return targetNpc != null || resolveStepLocationAnyConfidence(key) != null;
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
			nextStepKey = null;
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
				String key = step.getKey();
				// unfiltered: the compass/routing objective may use a
				// low-confidence point; the world-map PIN below still
				// honors the visibility filter
				StepLocationHint location = resolveStepLocationAnyConfidence(key);
				if (location != null && !isLocationSuppressed(key))
				{
					nextStepPoint = location.getPoint();
					nextStepKey = key;
					if (config.showNextStepOnMap() && visibleConfidence(location))
					{
						String prefix = location.isInferred() ? "next step near: " : "next step: ";
						setMarker(location.getPoint(), prefix + location.getLabel());
					}
					else
					{
						removeMapMarker();
					}
					return;
				}
				// Never skip past the current step to a later destination; that
				// would produce a confident but misleading route.
				nextStepPoint = null;
				nextStepKey = null;
				removeMapMarker();
				return;
			}
		}
		nextStepPoint = null;
		nextStepKey = null;
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
		if (point == null || !config.showWorldMapMarker() || !allowWorldMapGuidance(point))
		{
			return;
		}
		WorldMapPoint marker = new WorldMapPoint(point, MAP_MARKER_ICON);
		marker.setTooltip("Guide Overlay: " + name);
		// Stay visible when the destination is outside the viewed area: the
		// icon pins to the nearest edge instead of disappearing, and clicking
		// it pans the map straight there. Without this the marker was only
		// useful once you had already found roughly the right part of the map.
		marker.setSnapToEdge(true);
		marker.setJumpOnClick(true);
		// jumpOnClick uses the name as the jump target label
		marker.setName(name == null ? "Guide Overlay destination" : name);
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
