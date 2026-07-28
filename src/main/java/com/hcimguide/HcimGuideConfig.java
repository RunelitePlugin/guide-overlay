package com.hcimguide;

import java.awt.Color;
import java.awt.Font;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(HcimGuideConfig.GROUP)
public interface HcimGuideConfig extends Config
{
	String GROUP = "hcimguide";
	String COMPLETED_STEPS_KEY = "completedSteps";

	/**
	 * Font family for the plugin's text overlays. Physical-font presets use
	 * the named installed font when available and fall back to Java's logical
	 * sans-serif family when unavailable. The legacy constants remain so old
	 * saved config values continue to deserialize after upgrading.
	 */
	enum OverlayFontFamily
	{
		CLIENT_DEFAULT("Client default", null, Font.PLAIN),
		RUNESCAPE("RuneScape", null, Font.PLAIN),
		SANS_SERIF("Sans serif", Font.SANS_SERIF, Font.PLAIN),
		SERIF("Serif", Font.SERIF, Font.PLAIN),
		MONOSPACED("Monospaced", Font.MONOSPACED, Font.PLAIN),
		DIALOG("Dialog", Font.DIALOG, Font.PLAIN),
		DIALOG_INPUT("Dialog input", Font.DIALOG_INPUT, Font.PLAIN),
		ARIAL("Arial", "Arial", Font.PLAIN),
		CALIBRI("Calibri", "Calibri", Font.PLAIN),
		CAMBRIA("Cambria", "Cambria", Font.PLAIN),
		CANDARA("Candara", "Candara", Font.PLAIN),
		CONSOLAS("Consolas", "Consolas", Font.PLAIN),
		COURIER_NEW("Courier New", "Courier New", Font.PLAIN),
		GEORGIA("Georgia", "Georgia", Font.PLAIN),
		HELVETICA("Helvetica", "Helvetica", Font.PLAIN),
		INTER("Inter", "Inter", Font.PLAIN),
		JETBRAINS_MONO("JetBrains Mono", "JetBrains Mono", Font.PLAIN),
		LUCIDA_CONSOLE("Lucida Console", "Lucida Console", Font.PLAIN),
		MENLO("Menlo", "Menlo", Font.PLAIN),
		NOTO_SANS("Noto Sans", "Noto Sans", Font.PLAIN),
		OPEN_SANS("Open Sans", "Open Sans", Font.PLAIN),
		ROBOTO("Roboto", "Roboto", Font.PLAIN),
		SEGOE_UI("Segoe UI", "Segoe UI", Font.PLAIN),
		TAHOMA("Tahoma", "Tahoma", Font.PLAIN),
		TIMES_NEW_ROMAN("Times New Roman", "Times New Roman", Font.PLAIN),
		TREBUCHET_MS("Trebuchet MS", "Trebuchet MS", Font.PLAIN),
		VERDANA("Verdana", "Verdana", Font.PLAIN),
		CUSTOM("Custom installed font", null, Font.PLAIN),

		// Legacy persisted values from 1.15.2 and earlier.
		SMALL("RuneScape small (legacy)", null, Font.PLAIN),
		REGULAR("RuneScape regular (legacy)", null, Font.PLAIN),
		BOLD("RuneScape bold (legacy)", null, Font.BOLD),
		SANS_SMALL("Sans serif (legacy small)", Font.SANS_SERIF, Font.PLAIN),
		SANS("Sans serif (legacy)", Font.SANS_SERIF, Font.PLAIN),
		SANS_LARGE("Sans serif (legacy large)", Font.SANS_SERIF, Font.PLAIN);

		private final String label;
		private final String awtName;
		private final int defaultStyle;

		OverlayFontFamily(String label, String awtName, int defaultStyle)
		{
			this.label = label;
			this.awtName = awtName;
			this.defaultStyle = defaultStyle;
		}

		String getAwtName()
		{
			return awtName;
		}

		int getDefaultStyle()
		{
			return defaultStyle;
		}

		boolean isRuneScape()
		{
			return this == RUNESCAPE || this == SMALL || this == REGULAR || this == BOLD;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Font weight/style applied independently of the selected family. */
	enum OverlayFontWeight
	{
		FAMILY_DEFAULT("Family default", -1),
		PLAIN("Plain", Font.PLAIN),
		BOLD("Bold", Font.BOLD),
		ITALIC("Italic", Font.ITALIC),
		BOLD_ITALIC("Bold italic", Font.BOLD | Font.ITALIC);

		private final String label;
		private final int awtStyle;

		OverlayFontWeight(String label, int awtStyle)
		{
			this.label = label;
			this.awtStyle = awtStyle;
		}

		int resolveStyle(OverlayFontFamily family)
		{
			return awtStyle >= 0 ? awtStyle : family.getDefaultStyle();
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Where the clickable next/previous step arrows live. */
	enum ArrowMode
	{
		ATTACHED,
		FLOATING,
		HIDDEN
	}

	/** Compass needle style. */
	enum CompassArrowStyle
	{
		TRIANGLE,
		TAILED
	}

	/** Which parts of location guidance are visible. */
	enum GuidanceDisplayMode
	{
		ALL,
		WORLD_MAP_ONLY,
		SHORTEST_PATH_ONLY,
		NEARBY_ONLY,
		NO_COMPASS
	}

	// ------------------------------------------------------------------ sections

	@ConfigSection(
		name = "Target tracking",
		description = "Current-step target: colored scene arrow, target highlight and path-aligned compass",
		position = 0,
		closedByDefault = true
	)
	String targetSection = "target";

	@ConfigSection(
		name = "World map",
		description = "World map markers for the pinned target and the next step",
		position = 1,
		closedByDefault = true
	)
	String mapSection = "map";

	@ConfigSection(
		name = "Bank tags",
		description = "Tag and open the current bank's items via the built-in Bank Tags plugin",
		position = 2,
		closedByDefault = true
	)
	String bankSection = "bank";

	@ConfigSection(
		name = "On-screen HUD",
		description = "The movable on-screen step overlay. Alt+drag to reposition; every option below adjusts it.",
		position = 3,
		closedByDefault = true
	)
	String hudSection = "hud";

	@ConfigSection(
		name = "Step highlighting",
		description = "Outlines and tile highlights for the active bank's NPCs and items",
		position = 4,
		closedByDefault = true
	)
	String highlightSection = "highlight";

	@ConfigSection(
		name = "Auto-completion",
		description = "Automatic check-off of verifiable steps",
		position = 5,
		closedByDefault = true
	)
	String autoSection = "auto";

	@ConfigSection(
		name = "Side panel",
		description = "Side-panel text and layout appearance",
		position = 6,
		closedByDefault = true
	)
	String uiSection = "ui";

	@ConfigSection(
		name = "Progress",
		description = "How checklist progress is stored",
		position = 7,
		closedByDefault = true
	)
	String progressSection = "progress";

	@ConfigSection(
		name = "Routing & teleports",
		description = "Fastest-way-there suggestions using teleports you own (bank checked), plus optional path drawing via the Shortest Path plugin",
		position = 8,
		closedByDefault = true
	)
	String routeSection = "route";

	@ConfigSection(
		name = "Step navigation",
		description = "Clickable next/previous step arrows and optional keybinds. Next checks off your current step; Previous un-checks the last one.",
		position = 9,
		closedByDefault = true
	)
	String navSection = "nav";

	@ConfigSection(
		name = "Notifications & sounds",
		description = "Step, trip-ready and section-complete confirmations: on-screen/chat text plus optional sounds (trip-ready uses its own sound)",
		position = 10,
		closedByDefault = true
	)
	String notifySection = "notify";

	// ------------------------------------------------------------------ target tracking

	@ConfigItem(
		keyName = "enableHintArrow",
		name = "Colored target arrow",
		description = "Draw a persistent colored arrow above the current (or pinned) step's nearby NPC or destination tile",
		position = 1,
		section = targetSection
	)
	default boolean enableHintArrow()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "targetArrowColor",
		name = "Target arrow color",
		description = "Color and opacity of the custom arrow above the tracked target",
		position = 2,
		section = targetSection
	)
	default Color targetArrowColor()
	{
		return new Color(0, 255, 255, 230);
	}

	@Range(min = 8, max = 32)
	@ConfigItem(
		keyName = "targetArrowSize",
		name = "Target arrow size",
		description = "Size of the custom target arrow in pixels",
		position = 3,
		section = targetSection
	)
	default int targetArrowSize()
	{
		return 16;
	}

	@ConfigItem(
		keyName = "nativeHintArrow",
		name = "Native game hint arrow",
		description = "Also request the game's native hint arrow. Other plugins or the game may replace it; the colored overlay arrow remains independent.",
		position = 4,
		section = targetSection
	)
	default boolean nativeHintArrow()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "highlightColor",
		name = "Target highlight color",
		description = "Outline and label color for the guided step's target NPC",
		position = 5,
		section = targetSection
	)
	default Color highlightColor()
	{
		return new Color(0, 255, 255);
	}

	@Alpha
	@ConfigItem(
		keyName = "compassColor",
		name = "Compass color",
		description = "Color of the compass arrow, ring and distance text. Independent of the target highlight color.",
		position = 12,
		section = targetSection
	)
	default Color compassColor()
	{
		return new Color(0, 255, 255);
	}

	@ConfigItem(
		keyName = "showDirectionArrow",
		name = "Compass to path target",
		description = "Show a compass toward the destination handed to Shortest Path on every located step (in the 'All' location display mode; the hand-off drives the compass even when Shortest Path isn't installed). Without a hand-off target it falls back to the current far target. Movable with Alt+drag.",
		position = 6,
		section = targetSection
	)
	default boolean showDirectionArrow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "compassNextStep",
		name = "Compass without pinning",
		description = "When there is no hand-off target and the current or pinned step offers no target of its own, point the compass at the NEXT unchecked step's known far target. Turn off to disable only this fallback.",
		position = 7,
		section = targetSection
	)
	default boolean compassNextStep()
	{
		return true;
	}

	@ConfigItem(
		keyName = "compassShowRing",
		name = "Compass outer circle",
		description = "Draw the round dial behind the compass needle. Turn off for just the floating arrow.",
		position = 8,
		section = targetSection
	)
	default boolean compassShowRing()
	{
		return false;
	}

	@ConfigItem(
		keyName = "compassArrowStyle",
		name = "Arrow style",
		description = "Triangle: a solid pointer. Tailed: an arrow with a shaft, like a drawn arrow.",
		position = 9,
		section = targetSection
	)
	default CompassArrowStyle compassArrowStyle()
	{
		return CompassArrowStyle.TRIANGLE;
	}

	@Range(min = 32, max = 96)
	@ConfigItem(
		keyName = "compassSize",
		name = "Compass size",
		description = "Diameter of the path-target compass, in pixels",
		position = 10,
		section = targetSection
	)
	default int compassSize()
	{
		return 44;
	}

	@Range(min = 10, max = 100)
	@ConfigItem(
		keyName = "compassOpacity",
		name = "Compass opacity",
		description = "Opacity of the path-target compass (percent)",
		position = 11,
		section = targetSection
	)
	default int compassOpacity()
	{
		return 70;
	}

	@ConfigItem(
		keyName = "compassShowDistance",
		name = "Show tile distance",
		description = "Show the tile distance under the compass",
		position = 13,
		section = targetSection
	)
	default boolean compassShowDistance()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guidanceDisplayMode",
		name = "Location display mode",
		description = "Choose which native location guides are shown. The per-destination crosshair can still hide everything temporarily.",
		position = 14,
		section = targetSection
	)
	default GuidanceDisplayMode guidanceDisplayMode()
	{
		return GuidanceDisplayMode.ALL;
	}

	@ConfigItem(
		keyName = "distanceAwareGuidance",
		name = "Distance-aware guidance",
		description = "Far away: map/path. Nearby: compass, colored target arrow and scene highlights. Uses hysteresis to avoid flicker.",
		position = 15,
		section = targetSection
	)
	default boolean distanceAwareGuidance()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showLocationConfidence",
		name = "Panel location details",
		description = "Append the destination's source and confidence to the side panel's location status line. The HUD shows only its small location counter either way.",
		position = 16,
		section = targetSection
	)
	default boolean showLocationConfidence()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideLowConfidenceLocations",
		name = "Hide low-confidence pins",
		description = "Do not display locations classified as low confidence. Off by default so every resolved location produces guidance.",
		position = 17,
		section = targetSection
	)
	default boolean hideLowConfidenceLocations()
	{
		return false;
	}


	// ------------------------------------------------------------------ world map

	@ConfigItem(
		keyName = "showWorldMapMarker",
		name = "Pinned target marker",
		description = "Mark the pinned target's last known location on the world map",
		position = 1,
		section = mapSection
	)
	default boolean showWorldMapMarker()
	{
		return true;
	}

	@ConfigItem(
		keyName = "centerMapOnOpen",
		name = "Center map on target when opened",
		description = "When you open the world map, start it centered on the current step's destination. The map is never moved while you are using it.",
		position = 3,
		section = mapSection
	)
	default boolean centerMapOnOpen()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNextStepOnMap",
		name = "Next step marker",
		description = "When nothing is pinned, automatically mark the next unchecked step's target on the world map, so opening the map always shows where to go",
		position = 2,
		section = mapSection
	)
	default boolean showNextStepOnMap()
	{
		return true;
	}

	// ------------------------------------------------------------------ bank tags

	@ConfigItem(
		keyName = "bankTagIntegration",
		name = "Tag current bank items",
		description = "Keeps the CURRENT bank section's remaining withdraw items under a 'guide-overlay' bank tag and opens that tab when you open the bank - you still click and withdraw items yourself. Requires the built-in Bank Tags plugin. Turning this off removes the tag from every item.",
		position = 1,
		section = bankSection
	)
	default boolean bankTagIntegration()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bankTagUseLayout",
		name = "Apply bank tag layout",
		description = "Let the built-in Bank Tag Layouts feature arrange the guide-overlay tab (your saved layout, if any). Turn off to show items in a plain grid.",
		position = 2,
		section = bankSection
	)
	default boolean bankTagUseLayout()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bankTagStepOrder",
		name = "Arrange tab in guide order",
		description = "Automatically lay out the guide-overlay tab in the order the section needs the items (uses the built-in Bank Tag Layouts). Turn off to arrange the tab yourself.",
		position = 4,
		section = bankSection
	)
	default boolean bankTagStepOrder()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bankTagAutoOpen",
		name = "Open tab when bank opens",
		description = "Automatically switch to the guide-overlay tag tab each time you open the bank. Turn off to keep the tag but not change your tab.",
		position = 3,
		section = bankSection
	)
	default boolean bankTagAutoOpen()
	{
		return true;
	}

	// ------------------------------------------------------------------ step highlighting

	@ConfigItem(
		keyName = "highlightStepNpcs",
		name = "Highlight step NPCs",
		description = "Outline every NPC referenced by the unchecked steps of your current bank section, not just the pinned target",
		position = 1,
		section = highlightSection
	)
	default boolean highlightStepNpcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "stepNpcColor",
		name = "Step NPC color",
		description = "Outline color for NPCs referenced by current-bank steps",
		position = 2,
		section = highlightSection
	)
	default Color stepNpcColor()
	{
		return new Color(70, 160, 255);
	}

	@ConfigItem(
		keyName = "highlightGroundItems",
		name = "Highlight ground items",
		description = "Highlight the tiles of ground items that current-bank steps need you to pick up",
		position = 3,
		section = highlightSection
	)
	default boolean highlightGroundItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groundItemColor",
		name = "Ground item color",
		description = "Tile highlight color for needed ground items",
		position = 4,
		section = highlightSection
	)
	default Color groundItemColor()
	{
		return new Color(90, 220, 130);
	}

	@ConfigItem(
		keyName = "highlightStepObjects",
		name = "Highlight step objects",
		description = "Outline the scene object the CURRENT step mentions - the nearest ladder, staircase, altar or door of each type named by the step you are on. Never highlights objects for later steps.",
		position = 5,
		section = highlightSection
	)
	default boolean highlightStepObjects()
	{
		return true;
	}

	@ConfigItem(
		keyName = "stepObjectColor",
		name = "Step object color",
		description = "Outline color for the current step's mentioned object",
		position = 6,
		section = highlightSection
	)
	default Color stepObjectColor()
	{
		return new Color(64, 190, 240);
	}

	@ConfigItem(
		keyName = "highlightDialogOptions",
		name = "Highlight dialogue options",
		description = "When your current step notes the chat choices to pick - \"(2,1)\" - outline the right option in the dialogue box as each menu appears, but only while talking to the NPC (or using the object) that step names. Other conversations are never highlighted. You still click it yourself.",
		position = 7,
		section = highlightSection
	)
	default boolean highlightDialogOptions()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dialogOptionColor",
		name = "Dialogue option color",
		description = "Outline color for the suggested dialogue option",
		position = 8,
		section = highlightSection
	)
	default Color dialogOptionColor()
	{
		return new Color(255, 200, 60);
	}

	// ------------------------------------------------------------------ auto-completion

	@ConfigItem(
		keyName = "autoComplete",
		name = "Auto-complete steps",
		description = "Automatically check off steps the plugin can verify: quest started/finished, skill level reached, required items in inventory. Only steps in the first bank that still has unchecked steps are evaluated. You can always untick a step manually.",
		position = 1,
		section = autoSection
	)
	default boolean autoComplete()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoTrackNext",
		name = "Auto-track next target",
		description = "After a step auto-completes, automatically pin the next unchecked step that has an NPC target (colored arrow + highlight)",
		position = 2,
		section = autoSection
	)
	default boolean autoTrackNext()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyAutoComplete",
		name = "Chat message on auto-complete",
		description = "Print a game chat message when a step is auto-completed",
		position = 3,
		section = autoSection
	)
	default boolean notifyAutoComplete()
	{
		return false;
	}

	// ------------------------------------------------------------------ on-screen HUD

	@ConfigItem(
		keyName = "showHudOverlay",
		name = "Show HUD overlay",
		description = "Show a small movable overlay with your active bank and its next unchecked steps. Movable with Alt+drag.",
		position = 1,
		section = hudSection
	)
	default boolean showHudOverlay()
	{
		return true;
	}

	@Range(min = 1, max = 5)
	@ConfigItem(
		keyName = "hudMaxSteps",
		name = "Step count",
		description = "How many upcoming steps the on-screen overlay lists",
		position = 2,
		section = hudSection
	)
	default int hudMaxSteps()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "hudSplitNextSteps",
		name = "Separate 'Next steps' box",
		description = "Show the current step in its own box, with the upcoming steps in a second box underneath - easier to focus than one packed list. Applies when Step count is more than 1.",
		position = 3,
		section = hudSection
	)
	default boolean hudSplitNextSteps()
	{
		return true;
	}

	@Range(min = 160, max = 320)
	@ConfigItem(
		keyName = "hudWidth",
		name = "Width",
		description = "Width of the on-screen step overlay, in pixels",
		position = 4,
		section = hudSection
	)
	default int hudWidth()
	{
		return 230;
	}

	@ConfigItem(
		keyName = "overlayFontStyle",
		name = "Overlay font family",
		description = "Font used by the step HUD, compass distance and scene labels. Installed-font presets fall back safely when unavailable. Choose Custom to type any installed family name below.",
		position = 5,
		section = hudSection
	)
	default OverlayFontFamily overlayFontFamily()
	{
		return OverlayFontFamily.SANS_SERIF;
	}

	@ConfigItem(
		keyName = "overlayFontWeight",
		name = "Overlay font style",
		description = "Plain, bold, italic or bold italic, independent of the font family",
		position = 6,
		section = hudSection
	)
	default OverlayFontWeight overlayFontWeight()
	{
		return OverlayFontWeight.FAMILY_DEFAULT;
	}

	@Range(min = 8, max = 40)
	@ConfigItem(
		keyName = "overlayFontSize",
		name = "Overlay font size",
		description = "Text size for the step HUD, compass distance and scene labels, in pixels",
		position = 7,
		section = hudSection
	)
	default int overlayFontSize()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "customOverlayFontFamily",
		name = "Custom overlay font",
		description = "Exact installed font-family name used when Overlay font family is Custom. Unknown names fall back to Sans serif.",
		position = 8,
		section = hudSection
	)
	default String customOverlayFontFamily()
	{
		return "";
	}

	@ConfigItem(
		keyName = "hudShowStepItems",
		name = "Item pictures on HUD",
		description = "Show the current step's item pictures inside the on-screen box. The side panel's own item grids have a separate toggle under Side panel, so you can show items in either place, both, or neither.",
		position = 9,
		section = hudSection
	)
	default boolean hudShowStepItems()
	{
		return true;
	}

	@Range(min = 20, max = 100)
	@ConfigItem(
		keyName = "hudBackgroundOpacity",
		name = "Background opacity",
		description = "Opacity of the on-screen box's dark background (percent). Higher makes the text easier to read over busy scenes.",
		position = 10,
		section = hudSection
	)
	default int hudBackgroundOpacity()
	{
		return 85;
	}

	// ------------------------------------------------------------------ side panel

	@ConfigItem(
		keyName = "panelFontFamily",
		name = "Panel font family",
		description = "Font family used throughout the Guide Overlay side panel. Installed-font presets fall back safely when unavailable. Choose Custom to type any installed family name below.",
		position = 1,
		section = uiSection
	)
	default OverlayFontFamily panelFontFamily()
	{
		return OverlayFontFamily.SANS_SERIF;
	}

	@ConfigItem(
		keyName = "panelFontWeight",
		name = "Panel font style",
		description = "Plain, bold, italic or bold italic for the side panel, independent of the font family",
		position = 2,
		section = uiSection
	)
	default OverlayFontWeight panelFontWeight()
	{
		return OverlayFontWeight.PLAIN;
	}

	@Range(min = 8, max = 40)
	@ConfigItem(
		keyName = "panelFontSize",
		name = "Panel font size",
		description = "Text size throughout the side panel, in pixels",
		position = 3,
		section = uiSection
	)
	default int panelFontSize()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "customPanelFontFamily",
		name = "Custom panel font",
		description = "Exact installed font-family name used when Panel font family is Custom. Unknown names fall back to Sans serif.",
		position = 4,
		section = uiSection
	)
	default String customPanelFontFamily()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showItemGrids",
		name = "Show item icons",
		description = "Show an inventory-style grid of item icons under Withdraw/Collect steps, with green borders when the item is in your inventory",
		position = 5,
		section = uiSection
	)
	default boolean showItemGrids()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dimCompletedSteps",
		name = "Dim completed steps",
		description = "Gray out and strike through steps you have checked off",
		position = 6,
		section = uiSection
	)
	default boolean dimCompletedSteps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoCollapseCompleted",
		name = "Auto-collapse finished banks",
		description = "Collapse a bank section automatically once every step in it is checked",
		position = 7,
		section = uiSection
	)
	default boolean autoCollapseCompleted()
	{
		return true;
	}

	@Range(min = 0, max = 3)
	@ConfigItem(
		keyName = "preloadNextBanks",
		name = "Preload upcoming banks",
		description = "How many upcoming bank sections to warm up in the background (item icons resolved ahead of time) beyond the current one. 0 = load everything on demand; higher preloads more but does more background work.",
		position = 8,
		section = uiSection
	)
	default int preloadNextBanks()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "itemPresenceBorders",
		name = "Have-it borders on items",
		description = "Outline item icons green when you have the item, red when missing. Turn off for a clean, wiki-style inventory picture.",
		position = 9,
		section = uiSection
	)
	default boolean itemPresenceBorders()
	{
		return true;
	}

	@ConfigItem(
		keyName = "colorTransportSteps",
		name = "Cyan transport steps",
		description = "Color teleport and transport instructions cyan in the side panel and HUD so route changes stand out.",
		position = 10,
		section = uiSection
	)
	default boolean colorTransportSteps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "transportStepColor",
		name = "Transport text color",
		description = "Text color used for teleport, fairy-ring, boat, minecart, Quetzal, portal and similar transport steps.",
		position = 11,
		section = uiSection
	)
	default Color transportStepColor()
	{
		return new Color(80, 220, 255);
	}

	@ConfigItem(
		keyName = "colorDangerSteps",
		name = "Red danger steps",
		description = "Color Wilderness, deliberate-death, item-loss and other explicit high-risk instructions coral red.",
		position = 12,
		section = uiSection
	)
	default boolean colorDangerSteps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dangerStepColor",
		name = "Danger text color",
		description = "Text color used for explicit danger, Wilderness, death-risk and item-loss instructions.",
		position = 13,
		section = uiSection
	)
	default Color dangerStepColor()
	{
		return new Color(255, 107, 107);
	}

	@ConfigItem(
		keyName = "colorPreparationSteps",
		name = "Amber preparation steps",
		description = "Color withdrawals, equipment setup, charges and explicit prerequisites amber.",
		position = 14,
		section = uiSection
	)
	default boolean colorPreparationSteps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "preparationStepColor",
		name = "Preparation text color",
		description = "Text color used for item setup, equipment, charges, minimum requirements and before-leaving reminders.",
		position = 15,
		section = uiSection
	)
	default Color preparationStepColor()
	{
		return new Color(255, 200, 87);
	}

	// ------------------------------------------------------------------ routing & teleports

	@ConfigItem(
		keyName = "routeSuggestions",
		name = "Suggest fastest route",
		description = "Show the fastest teleport toward the current objective on the HUD - only teleports whose runes/jewelry you actually have (carried, or in the bank). Suggestion only; you always click the teleport yourself.",
		position = 1,
		section = routeSection
	)
	default boolean routeSuggestions()
	{
		return true;
	}

	@ConfigItem(
		keyName = "routeUseShortestPath",
		name = "Draw path (Shortest Path plugin)",
		description = "Hand the current objective to the community 'Shortest Path' plugin (if installed from the Plugin Hub) so it draws the actual tile path with your transport settings. Does nothing when that plugin is absent.",
		position = 2,
		section = routeSection
	)
	default boolean routeUseShortestPath()
	{
		return true;
	}

	@Range(min = 0, max = 20)
	@ConfigItem(
		keyName = "pathRefreshTiles",
		name = "Path refresh distance",
		description = "Shortest Path only removes the part of the route you have already walked when it recalculates, so this asks it to recalculate after you move this many tiles. LOWER = smoother trailing edge but SIGNIFICANTLY more CPU work, because a full route is recalculated every few tiles; on long cross-map routes a low value can make the path lag further behind instead of less. HIGHER = much less load, at the cost of the walked tail lingering longer. 0 disables it entirely and the path only refreshes when the step changes. Recalculation happens inside the Shortest Path plugin, not this one.",
		position = 9,
		section = routeSection
	)
	default int pathRefreshTiles()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "routeIncludeBanked",
		name = "Count banked items",
		description = "Also suggest teleports whose runes/jewelry are in your bank (marked 'in bank'). Bank contents refresh whenever you open the bank.",
		position = 3,
		section = routeSection
	)
	default boolean routeIncludeBanked()
	{
		return true;
	}

	@ConfigItem(
		keyName = "routeSpells",
		name = "Spellbook teleports",
		description = "Consider standard spellbook teleports (rune costs bank-checked)",
		position = 4,
		section = routeSection
	)
	default boolean routeSpells()
	{
		return true;
	}

	@ConfigItem(
		keyName = "routeTabs",
		name = "Teleport tablets",
		description = "Consider teleport tablets you own",
		position = 5,
		section = routeSection
	)
	default boolean routeTabs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "routeJewelry",
		name = "Jewelry teleports",
		description = "Consider charged jewelry (glory, dueling, games, passage, skills, combat)",
		position = 6,
		section = routeSection
	)
	default boolean routeJewelry()
	{
		return true;
	}

	@ConfigItem(
		keyName = "routeOther",
		name = "Other teleports",
		description = "Consider other teleport items (Ectophial, Chronicle, ...)",
		position = 7,
		section = routeSection
	)
	default boolean routeOther()
	{
		return true;
	}

	@ConfigItem(
		keyName = "routeExcluded",
		name = "Excluded teleports",
		description = "Comma-separated names to never suggest, matched loosely - e.g. 'Karamja, Castle Wars, Home Teleport'",
		position = 8,
		section = routeSection
	)
	default String routeExcluded()
	{
		return "";
	}

	// ------------------------------------------------------------------ step navigation

	@ConfigItem(
		keyName = "navArrows",
		name = "Arrow buttons",
		description = "Where the clickable ◀ ▶ step arrows appear: Attached sits at the top of the on-screen HUD box, centered, so the row keeps a fixed position as the text below changes (needs the HUD overlay on). Floating is its own small overlay you can Alt+drag anywhere. Hidden removes them. Clicking ▶ checks off your current step; ◀ un-checks the last one.",
		position = 1,
		section = navSection
	)
	default ArrowMode navArrows()
	{
		return ArrowMode.ATTACHED;
	}

	@ConfigItem(
		keyName = "navKeybindsEnabled",
		name = "Enable keybinds",
		description = "Master switch for the next/previous step keybinds below",
		position = 2,
		section = navSection
	)
	default boolean navKeybindsEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "nextStepKeybind",
		name = "Next step key",
		description = "Marks your current step complete and advances to the next. Unbound by default - click and press any key combo. Prefer combos or F-keys over plain letters if you type in chat.",
		position = 3,
		section = navSection
	)
	default Keybind nextStepKeybind()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "prevStepKeybind",
		name = "Previous step key",
		description = "Un-checks your most recently completed step and moves back to it. Unbound by default - click and press any key combo.",
		position = 4,
		section = navSection
	)
	default Keybind prevStepKeybind()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "autoAdvanceWaypoints",
		name = "Auto-advance waypoints",
		description = "Advance to the next waypoint after remaining inside its arrival radius for the required game ticks.",
		position = 5,
		section = navSection
	)
	default boolean autoAdvanceWaypoints()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoCompleteOnArrival",
		name = "Complete travel steps on arrival",
		description = "Check off explicit travel-only steps after you remain at their final waypoint. Compound instructions, NPC interactions, inherited/low-confidence locations, quest/item/skill conditions, and parent summary steps are never completed by arrival.",
		position = 6,
		section = navSection
	)
	default boolean autoCompleteOnArrival()
	{
		return true;
	}

	@ConfigItem(
		keyName = "persistWaypointIndex",
		name = "Remember waypoint position",
		description = "Remember the active waypoint for each step across client restarts.",
		position = 7,
		section = navSection
	)
	default boolean persistWaypointIndex()
	{
		return true;
	}

	@ConfigItem(
		keyName = "preferQuestHelperMarkers",
		name = "Prefer Quest Helper markers",
		description = "For quest-stage steps, suppress duplicate Guide Overlay NPC/object highlights while keeping the checklist, map destination and Shortest Path route.",
		position = 8,
		section = navSection
	)
	default boolean preferQuestHelperMarkers()
	{
		return false;
	}

	// ------------------------------------------------------------------ notifications & sounds

	enum SoundSource
	{
		GAME,
		PLUGIN;

		@Override
		public String toString()
		{
			return this == GAME ? "Game (follows game volume)" : "Plugin (works when muted)";
		}
	}

	@ConfigItem(
		keyName = "soundSource",
		name = "Sound source",
		description = "Game plays through the client, so the game's master and sound-effect volumes must both be on. Plugin generates the chime itself and works with the game muted.",
		position = -1,
		section = notifySection
	)
	default SoundSource soundSource()
	{
		return SoundSource.PLUGIN;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "pluginSoundVolume",
		name = "Plugin sound volume",
		description = "Volume for plugin-generated chimes. Independent of the game's volume. 0 silences them.",
		position = -1,
		section = notifySection
	)
	default int pluginSoundVolume()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "stepCompleteSound",
		name = "Step complete sound",
		description = "Play the success chime whenever a step gets checked off - by you or by auto-completion. Bulk actions (mark bank complete, account sync) stay silent.",
		position = 0,
		section = notifySection
	)
	default boolean stepCompleteSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "tripReadyIndicator",
		name = "Trip-ready indicator",
		description = "Show a green \"✓ Trip ready\" line on the on-screen box once every item the current section still needs is in your inventory",
		position = 1,
		section = notifySection
	)
	default boolean tripReadyIndicator()
	{
		return true;
	}

	@ConfigItem(
		keyName = "tripReadySound",
		name = "Trip-ready sound",
		description = "Play a short sound (distinct from the success chime) the moment the trip becomes ready (once per section)",
		position = 2,
		section = notifySection
	)
	default boolean tripReadySound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bankCompleteMessage",
		name = "Section complete message",
		description = "Print a game chat message when you finish the last step of a bank section",
		position = 3,
		section = notifySection
	)
	default boolean bankCompleteMessage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bankCompleteSound",
		name = "Section complete sound",
		description = "Play a short success sound when a bank section completes",
		position = 4,
		section = notifySection
	)
	default boolean bankCompleteSound()
	{
		return true;
	}

	// ------------------------------------------------------------------ progress

	@ConfigItem(
		keyName = "perCharacterProgress",
		name = "Per-character progress",
		description = "Track checklist progress separately for each RuneScape character (recommended for HCIM: a new character after a death starts fresh while the old one's is preserved). When first enabled for a character, existing shared progress is copied over once.",
		position = 1,
		section = progressSection
	)
	default boolean perCharacterProgress()
	{
		return true;
	}
}
