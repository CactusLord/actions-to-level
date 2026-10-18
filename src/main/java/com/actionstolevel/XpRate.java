package com.actionstolevel;

import net.runelite.api.Skill;

/**
 * XP for one item/action in one skill, and where that number came from.
 */
final class XpRate
{
	final Skill skill;
	final double xp;
	/** Level needed to do this, or 0 if unknown. */
	final int levelRequired;
	/** True when the value was measured from your own XP drops rather than the base table. */
	final boolean measured;
	/** For objects, the item it gives (e.g. "Iron ore" for "Iron rocks"); otherwise null. */
	final String gives;

	XpRate(Skill skill, double xp, int levelRequired, boolean measured, String gives)
	{
		this.skill = skill;
		this.xp = xp;
		this.levelRequired = levelRequired;
		this.measured = measured;
		this.gives = gives;
	}
}
