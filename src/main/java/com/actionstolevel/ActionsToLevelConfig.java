package com.actionstolevel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ActionsToLevelConfig.GROUP)
public interface ActionsToLevelConfig extends Config
{
	String GROUP = "actionstolevel";
	String LEARNED_RATES_KEY = "learnedRates";
	String MONSTERS_KEY = "monsters";

	@ConfigSection(
		name = "Display",
		description = "How the countdown is shown",
		position = 0
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Goals and ironman",
		description = "Goals, counting what's in your bank, and what you still need to gather",
		position = 1
	)
	String goalsSection = "goals";

	@ConfigSection(
		name = "Right-click and hover",
		description = "Where the 'Actions to level' option and tooltips appear",
		position = 2
	)
	String menuSection = "menu";

	@ConfigSection(
		name = "Alerts",
		description = "Notifications near a level, level-up unlocks and goals reached",
		position = 3
	)
	String alertsSection = "alerts";

	@ConfigSection(
		name = "Combat",
		description = "Kills to level for monsters",
		position = 4
	)
	String combatSection = "combat";

	@ConfigSection(
		name = "Tracking",
		description = "Which level to count to and how XP is matched",
		position = 5
	)
	String trackingSection = "tracking";

	// ---- Display ----

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Show a box with how many more of what you're doing you need to level",
		position = 0,
		section = displaySection
	)
	default boolean showOverlay()
	{
		return true;
	}

	@Range(min = 1, max = 10)
	@ConfigItem(
		keyName = "maxEntries",
		name = "Items shown",
		description = "How many recent items, actions or monsters the overlay lists at once",
		position = 1,
		section = displaySection
	)
	default int maxEntries()
	{
		return 4;
	}

	@Range(max = 60)
	@Units(Units.MINUTES)
	@ConfigItem(
		keyName = "overlayTimeout",
		name = "Hide after",
		description = "Remove something from the overlay after this long without XP from it (0 = never)",
		position = 2,
		section = displaySection
	)
	default int overlayTimeout()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "showTimeToLevel",
		name = "Show time to level",
		description = "Estimate the time to level from your recent pace",
		position = 3,
		section = displaySection
	)
	default boolean showTimeToLevel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPerHour",
		name = "Show per hour",
		description = "Show actions or kills per hour instead of XP each",
		position = 4,
		section = displaySection
	)
	default boolean showPerHour()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chatOnXpDrop",
		name = "Chat message on each XP drop",
		description = "Also print the remaining count in the chatbox every time you gain XP (can be spammy)",
		position = 5,
		section = displaySection
	)
	default boolean chatOnXpDrop()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPanel",
		name = "Show side panel",
		description = "Add a sidebar button with search, goals, what to gather, your Slayer task and session stats",
		position = 6,
		section = displaySection
	)
	default boolean showPanel()
	{
		return true;
	}

	// ---- Goals and ironman ----

	@ConfigItem(
		keyName = "showBankCounts",
		name = "Count what I have",
		description = "Count your bank, inventory and worn items: 'you have 340, enough to reach 66'. Open your bank once so it can see it",
		position = 0,
		section = goalsSection
	)
	default boolean showBankCounts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGoalsOnOverlay",
		name = "Goals on overlay",
		description = "Show your goals and the first thing each is short of on the overlay",
		position = 1,
		section = goalsSection
	)
	default boolean showGoalsOnOverlay()
	{
		return true;
	}

	@Range(min = 1, max = 5)
	@ConfigItem(
		keyName = "goalsOnOverlay",
		name = "Goals shown",
		description = "How many goals the overlay lists",
		position = 2,
		section = goalsSection
	)
	default int goalsOnOverlay()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "prayerAltars",
		name = "Prayer altar maths",
		description = "For bones and ashes, also show the gilded altar, Ectofuntus, Chaos altar and Demonic offering",
		position = 3,
		section = goalsSection
	)
	default boolean prayerAltars()
	{
		return true;
	}

	// ---- Right-click and hover ----

	@ConfigItem(
		keyName = "menuOnItems",
		name = "Right-click items",
		description = "Add 'Actions to level' when right-clicking items (inventory, bank, ground)",
		position = 0,
		section = menuSection
	)
	default boolean menuOnItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "menuOnObjects",
		name = "Right-click objects",
		description = "Add 'Actions to level' when right-clicking objects such as rocks and trees",
		position = 1,
		section = menuSection
	)
	default boolean menuOnObjects()
	{
		return true;
	}

	@ConfigItem(
		keyName = "menuOnNpcs",
		name = "Right-click NPCs",
		description = "Add 'Actions to level' when right-clicking NPCs such as monsters, fishing spots or pickpocket targets",
		position = 2,
		section = menuSection
	)
	default boolean menuOnNpcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onlyWhenKnown",
		name = "Only when XP is known",
		description = "Only add the option for things the plugin has an XP value for. Turn off to show it on everything",
		position = 3,
		section = menuSection
	)
	default boolean onlyWhenKnown()
	{
		return true;
	}

	@ConfigItem(
		keyName = "requireShift",
		name = "Require Shift",
		description = "Only add the option while holding Shift",
		position = 4,
		section = menuSection
	)
	default boolean requireShift()
	{
		return false;
	}

	@ConfigItem(
		keyName = "goalMenuOption",
		name = "Shift: 'Add goal'",
		description = "While holding Shift, also add 'Add goal' to set a goal from what you right-click",
		position = 5,
		section = menuSection
	)
	default boolean goalMenuOption()
	{
		return true;
	}

	@ConfigItem(
		keyName = "tooltipItems",
		name = "Tooltip on items",
		description = "Show the counts when hovering over items in your inventory, bank or on the ground",
		position = 6,
		section = menuSection
	)
	default boolean tooltipItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "tooltipWorld",
		name = "Tooltip on rocks, trees and monsters",
		description = "Show the counts when hovering over objects and NPCs in the game world",
		position = 7,
		section = menuSection
	)
	default boolean tooltipWorld()
	{
		return false;
	}

	// ---- Alerts ----

	@ConfigItem(
		keyName = "nearLevelNotification",
		name = "Almost levelled",
		description = "Notify when you're this close to a level with what you're doing",
		position = 0,
		section = alertsSection
	)
	default Notification nearLevelNotification()
	{
		return Notification.ON;
	}

	@Range(min = 1, max = 1000)
	@ConfigItem(
		keyName = "alertAt",
		name = "Almost: actions left",
		description = "How many actions or kills left counts as almost there",
		position = 1,
		section = alertsSection
	)
	default int alertAt()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "unlockMessages",
		name = "Level-up unlocks",
		description = "On level up, say what you can now do and what the next unlock is",
		position = 2,
		section = alertsSection
	)
	default boolean unlockMessages()
	{
		return true;
	}

	@ConfigItem(
		keyName = "goalNotification",
		name = "Goal reached",
		description = "Notify when you reach one of your goals",
		position = 3,
		section = alertsSection
	)
	default Notification goalNotification()
	{
		return Notification.ON;
	}

	// ---- Combat ----

	@ConfigItem(
		keyName = "trackKills",
		name = "Track kills",
		description = "Count down the kills left to level when you kill monsters",
		position = 0,
		section = combatSection
	)
	default boolean trackKills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSlayer",
		name = "Show Slayer",
		description = "Show kills left for your next Slayer level (only counts while on task)",
		position = 1,
		section = combatSection
	)
	default boolean showSlayer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showHitpoints",
		name = "Show Hitpoints on overlay",
		description = "Also list Hitpoints on the overlay. Right-click always shows it",
		position = 2,
		section = combatSection
	)
	default boolean showHitpoints()
	{
		return false;
	}

	// ---- Tracking ----

	@Range(max = 126)
	@ConfigItem(
		keyName = "targetLevel",
		name = "Count to level",
		description = "Count towards this level instead of the next one (0 = next level). Ignored once you pass it",
		position = 0,
		section = trackingSection
	)
	default int targetLevel()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "virtualLevels",
		name = "Virtual levels past 99",
		description = "Keep counting towards virtual levels (100-126) after 99",
		position = 1,
		section = trackingSection
	)
	default boolean virtualLevels()
	{
		return false;
	}

	@ConfigItem(
		keyName = "learnRates",
		name = "Learn my real XP rates",
		description = "Use the XP you actually get (outfit bonuses, altars, monster XP bonuses, spell XP) once it has been seen twice, and learn recipes from what you make",
		position = 2,
		section = trackingSection
	)
	default boolean learnRates()
	{
		return true;
	}
}
