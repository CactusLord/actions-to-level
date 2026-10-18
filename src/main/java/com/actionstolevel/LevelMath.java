package com.actionstolevel;

import net.runelite.api.Experience;

/**
 * Pure level/XP arithmetic, kept free of client state so it is easy to reason about.
 */
final class LevelMath
{
	private LevelMath()
	{
	}

	/**
	 * Result of working out how far a skill is from its goal.
	 */
	static final class Progress
	{
		/** Current (virtual) level for the XP, 1-126. */
		final int currentLevel;
		/** Level being counted towards, or -1 when there is nothing left to count to. */
		final int targetLevel;
		/** XP still needed to reach {@link #targetLevel}. */
		final int xpRemaining;
		/** How many more actions of the given XP are needed, or -1 when maxed or the XP is unknown. */
		final int actionsRemaining;

		Progress(int currentLevel, int targetLevel, int xpRemaining, int actionsRemaining)
		{
			this.currentLevel = currentLevel;
			this.targetLevel = targetLevel;
			this.xpRemaining = xpRemaining;
			this.actionsRemaining = actionsRemaining;
		}

		boolean isMaxed()
		{
			return targetLevel < 0;
		}
	}

	/**
	 * @param currentXp       the player's XP in the skill
	 * @param configuredGoal  a goal level from config, or 0 for "next level"
	 * @param virtualLevels   whether to keep counting past 99
	 * @param xpPerAction     XP gained per item/action
	 */
	static Progress progress(int currentXp, int configuredGoal, boolean virtualLevels, double xpPerAction)
	{
		final int xp = Math.max(0, currentXp);
		final int currentLevel = Experience.getLevelForXp(xp);
		final int target = targetLevel(currentLevel, configuredGoal, virtualLevels);

		if (target < 0 || xp >= Experience.MAX_SKILL_XP)
		{
			return new Progress(currentLevel, -1, 0, -1);
		}

		final int xpRemaining = Math.max(0, Experience.getXpForLevel(target) - xp);
		return new Progress(currentLevel, target, xpRemaining, actionsFor(xpRemaining, xpPerAction));
	}

	static int targetLevel(int currentLevel, int configuredGoal, boolean virtualLevels)
	{
		if (configuredGoal > currentLevel)
		{
			return Math.min(configuredGoal, Experience.MAX_VIRT_LEVEL);
		}

		final int cap = virtualLevels ? Experience.MAX_VIRT_LEVEL : Experience.MAX_REAL_LEVEL;
		final int next = currentLevel + 1;
		return next > cap ? -1 : next;
	}

	static int actionsFor(int xpRemaining, double xpPerAction)
	{
		if (xpPerAction <= 0 || Double.isNaN(xpPerAction) || Double.isInfinite(xpPerAction))
		{
			return -1;
		}
		if (xpRemaining <= 0)
		{
			return 0;
		}
		// Tiny epsilon so 1050 / 17.5 = 60 is not rounded up to 61 by floating point noise
		return (int) Math.ceil(xpRemaining / xpPerAction - 1e-9);
	}
}
