package com.hcimguide;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(HcimGuideConfig.GROUP)
public interface HcimGuideConfig extends Config
{
	String GROUP = "hcimguide";
	String COMPLETED_STEPS_KEY = "completedSteps";

	/**
	 * Font choice for the plugin's overlays (HUD, compass, NPC labels).
	 * RuneLite's config UI title-cases the enum names for display.
	 */
	enum FontStyle
	{
		CLIENT_DEFAULT,
		SMALL,
		REGULAR,
		BOLD
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
		description = "Pinned-step target: hint arrow, far-target compass, world map marker",
		position = 0
	)
	String targetSection = "target";

	@ConfigSection(
		name = "Step highlighting",
		description = "Outlines and tile highlights for the active bank's NPCs and items",
		position = 1
	)
	String highlightSection = "highlight";

	@ConfigSection(
		name = "Auto-completion",
		description = "Automatic check-off of verifiable steps",
		position = 2
	)
	String autoSection = "auto";

	@ConfigSection(
		name = "Panel & overlays",
		description = "Side panel and on-screen overlay appearance. All overlays can be MOVED by holding Alt and dragging.",
		position = 3
	)
	String uiSection = "ui";

	@ConfigSection(
		name = "Progress",
		description = "How checklist progress is stored",
		position = 4
	)
	String progressSection = "progress";

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
		description = "When the pinned target is too far away to be nearby, show a compass arrow toward its last known location. Movable with Alt+drag.",
		position = 3,
		section = targetSection
	)
	default boolean showDirectionArrow()
	{
		return true;
	}

	@Range(min = 32, max = 96)
	@ConfigItem(
		keyName = "compassSize",
		name = "Compass size",
		description = "Diameter of the far-target compass, in pixels",
		position = 4,
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
		position = 5,
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
		position = 6,
		section = targetSection
	)
	default boolean compassShowDistance()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showWorldMapMarker",
		name = "World map marker",
		description = "Mark the pinned target's last known location on the world map",
		position = 7,
		section = targetSection
	)
	default boolean showWorldMapMarker()
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

	// ------------------------------------------------------------------ panel & overlays

	@ConfigItem(
		keyName = "showHudOverlay",
		name = "On-screen step overlay",
		description = "Show a small movable overlay with your active bank and its next unchecked steps. Movable with Alt+drag.",
		position = 1,
		section = uiSection
	)
	default boolean showHudOverlay()
	{
		return true;
	}

	@Range(min = 1, max = 5)
	@ConfigItem(
		keyName = "hudMaxSteps",
		name = "Overlay step count",
		description = "How many upcoming steps the on-screen overlay lists",
		position = 2,
		section = uiSection
	)
	default int hudMaxSteps()
	{
		return 3;
	}

	@Range(min = 160, max = 320)
	@ConfigItem(
		keyName = "hudWidth",
		name = "Overlay width",
		description = "Width of the on-screen step overlay, in pixels",
		position = 3,
		section = uiSection
	)
	default int hudWidth()
	{
		return 230;
	}

	@ConfigItem(
		keyName = "overlayFontStyle",
		name = "Overlay font",
		description = "Font used by this plugin's overlays (step HUD, compass distance, NPC name labels). 'Client default' follows RuneLite's own overlay font setting.",
		position = 4,
		section = uiSection
	)
	default FontStyle overlayFontStyle()
	{
		return FontStyle.CLIENT_DEFAULT;
	}

	@ConfigItem(
		keyName = "panelTextSize",
		name = "Panel text size",
		description = "Text size of steps in the side panel",
		position = 5,
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
		position = 6,
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
		position = 7,
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
		position = 8,
		section = uiSection
	)
	default boolean autoCollapseCompleted()
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
