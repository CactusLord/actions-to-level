package com.actionstolevel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ActionsToLevelConfig.GROUP)
public interface ActionsToLevelConfig extends Config
{
	String GROUP = "actionstolevel";
	String LEARNED_RATES_KEY = "learnedRates";

	@ConfigSection(
		name = "Display",
		description = "How the countdown is shown",
		position = 0
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Right-click option",
		description = "Where the 'Actions to level' option appears",
		position = 1
	)
	String menuSection = "menu";

	@ConfigSection(
		name = "Goal",
		description = "Which level to count towards",
		position = 2
	)
	String goalSection = "goal";

	@ConfigSection(
		name = "Tracking",
		description = "How XP drops are matched to items",
		position = 3
	)
	String trackingSection = "tracking";

	// ---- Display ----

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Show a box with how many more of the item you're working with you need to level",
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
		description = "How many recent items/actions the overlay lists at once",
		position = 1,
		section = displaySection
	)
	default int maxEntries()
	{
		return 3;
	}

	@Range(max = 60)
	@Units(Units.MINUTES)
	@ConfigItem(
		keyName = "overlayTimeout",
		name = "Hide after",
		description = "Remove an item from the overlay after this long without XP from it (0 = never)",
		position = 2,
		section = displaySection
	)
	default int overlayTimeout()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "chatOnXpDrop",
		name = "Chat message on each XP drop",
		description = "Also print the remaining count in the chatbox every time you gain XP (can be spammy)",
		position = 3,
		section = displaySection
	)
	default boolean chatOnXpDrop()
	{
		return false;
	}

	// ---- Right-click option ----

	@ConfigItem(
		keyName = "menuOnItems",
		name = "On items",
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
		name = "On objects",
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
		name = "On NPCs",
		description = "Add 'Actions to level' when right-clicking NPCs such as fishing spots or pickpocket targets",
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

	// ---- Goal ----

	@Range(max = 126)
	@ConfigItem(
		keyName = "targetLevel",
		name = "Target level",
		description = "Count towards this level instead of the next one (0 = next level). Ignored once you pass it",
		position = 0,
		section = goalSection
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
		section = goalSection
	)
	default boolean virtualLevels()
	{
		return false;
	}

	// ---- Tracking ----

	@ConfigItem(
		keyName = "learnRates",
		name = "Learn my real XP rates",
		description = "Use the XP you actually get (outfit bonuses, altars, etc.) once it has been seen twice, instead of the base value",
		position = 0,
		section = trackingSection
	)
	default boolean learnRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "ignoreCombat",
		name = "Ignore combat XP",
		description = "Don't track Attack, Strength, Defence, Ranged, Hitpoints and Slayer XP drops",
		position = 1,
		section = trackingSection
	)
	default boolean ignoreCombat()
	{
		return true;
	}
}
