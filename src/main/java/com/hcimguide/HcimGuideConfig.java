package com.hcimguide;

import java.awt.Color;
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
	 * Font choice for the plugin's overlays (HUD, compass, NPC labels).
	 * RuneLite's config UI title-cases the enum names for display. The SANS_*
	 * options use the system sans-serif font - plainer than the RuneScape
	 * fonts but noticeably easier to read at small sizes.
	 */
	enum FontStyle
	{
		CLIENT_DEFAULT,
		SMALL,
		REGULAR,
		BOLD,
		SANS_SMALL,
		SANS,
		SANS_LARGE
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

	/** Text size for step text in the side panel. */
	enum PanelTextSize
	{
		SMALL(11),
		REGULAR(12),
		LARGE(14);

		private final int px;

		PanelTextSize(int px)
		{
			this.px = px;
		}

		public int getPx()
		{
			return px;
		}
	}

	// ------------------------------------------------------------------ sections

	@ConfigSection(
		name = "Target tracking",
		description = "Pinned-step target: hint arrow, target highlight, far-target compass",
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
		description = "Trip-ready and section-complete confirmations: on-screen/chat text plus an optional success sound",
		position = 10,
		closedByDefault = true
	)
	String notifySection = "notify";

	// ------------------------------------------------------------------ target tracking

	@ConfigItem(
		keyName = "enableHintArrow",
		name = "Hint arrow to target",
		description = "Show the in-game hint arrow when the pinned step's NPC (or its known location) is nearby",
		position = 1,
		section = targetSection
	)
	default boolean enableHintArrow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightColor",
		name = "Target highlight color",
		description = "Outline color for the pinned step's target NPC and the compass accent color",
		position = 2,
		section = targetSection
	)
	default Color highlightColor()
	{
		return new Color(0, 255, 255);
	}

	@ConfigItem(
		keyName = "showDirectionArrow",
		name = "Compass to far targets",
		description = "When your target is too far away to be nearby, show a compass arrow toward its last known location. Movable with Alt+drag.",
		position = 3,
		section = targetSection
	)
	default boolean showDirectionArrow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "compassNextStep",
		name = "Compass without pinning",
		description = "With nothing pinned, point the compass at the NEXT unchecked step's known target, so it's there whenever there's somewhere to go. Turn off to only show the compass for steps you pin with ⌖.",
		position = 4,
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
		position = 5,
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
		position = 6,
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
		description = "Diameter of the far-target compass, in pixels",
		position = 7,
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
		description = "Opacity of the far-target compass (percent)",
		position = 8,
		section = targetSection
	)
	default int compassOpacity()
	{
		return 70;
	}

	@ConfigItem(
		keyName = "compassShowDistance",
		name = "Show tile distance",
		description = "Show the tile distance under the far-target compass",
		position = 9,
		section = targetSection
	)
	default boolean compassShowDistance()
	{
		return true;
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
		description = "Outline scene objects the current section's steps mention - ladders, staircases, altars, doors, furnaces and the like - so the \"which ladder?\" moments answer themselves",
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
		description = "Outline color for objects referenced by current-section steps",
		position = 6,
		section = highlightSection
	)
	default Color stepObjectColor()
	{
		return new Color(210, 140, 255);
	}

	@ConfigItem(
		keyName = "highlightDialogOptions",
		name = "Highlight dialogue options",
		description = "When your current step notes the chat choices to pick - \"(2,1)\" - outline the right option in the dialogue box as each menu appears. You still click it yourself.",
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
		description = "After a step auto-completes, automatically pin the next unchecked step that has an NPC target (hint arrow + highlight)",
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
		name = "Overlay font",
		description = "Font used by this plugin's overlays (step HUD, compass distance, NPC name labels). 'Client default' follows RuneLite's own overlay font setting; the Sans options are plainer but easier to read.",
		position = 5,
		section = hudSection
	)
	default FontStyle overlayFontStyle()
	{
		return FontStyle.SMALL;
	}

	@ConfigItem(
		keyName = "hudShowStepItems",
		name = "Item pictures on HUD",
		description = "Show the current step's item pictures inside the on-screen box. The side panel's own item grids have a separate toggle under Side panel, so you can show items in either place, both, or neither.",
		position = 6,
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
		position = 7,
		section = hudSection
	)
	default int hudBackgroundOpacity()
	{
		return 85;
	}

	// ------------------------------------------------------------------ side panel

	@ConfigItem(
		keyName = "panelTextSize",
		name = "Panel text size",
		description = "Text size of steps in the side panel",
		position = 1,
		section = uiSection
	)
	default PanelTextSize panelTextSize()
	{
		return PanelTextSize.REGULAR;
	}

	@ConfigItem(
		keyName = "showItemGrids",
		name = "Show item icons",
		description = "Show an inventory-style grid of item icons under Withdraw/Collect steps, with green borders when the item is in your inventory",
		position = 2,
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
		position = 3,
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
		position = 4,
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
		position = 5,
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
		position = 6,
		section = uiSection
	)
	default boolean itemPresenceBorders()
	{
		return true;
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
		description = "Where the clickable ◀ ▶ step arrows appear: Attached sits under the on-screen HUD box (needs the HUD overlay on), Floating is its own small overlay you can Alt+drag anywhere, Hidden removes them. Clicking ▶ checks off your current step; ◀ un-checks the last one.",
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

	// ------------------------------------------------------------------ notifications & sounds

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
		description = "Play a short success sound the moment the trip becomes ready (once per section)",
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
