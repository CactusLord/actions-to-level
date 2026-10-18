package com.actionstolevel;

import net.runelite.api.Skill;

/**
 * XP for one item/action/kill in one skill, and where that number came from.
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
	/** True when one "action" is a kill. */
	final boolean perKill;
	/** Extra text shown after the count, e.g. Slayer task status. */
	final String note;
	/** The item each action uses up (logs burned, bones buried, raw fish cooked), or -1. */
	final int consumesItemId;

	XpRate(Skill skill, double xp, int levelRequired, boolean measured, String gives)
	{
		this(skill, xp, levelRequired, measured, gives, false, null, -1);
	}

	private XpRate(Skill skill, double xp, int levelRequired, boolean measured, String gives, boolean perKill, String note, int consumesItemId)
	{
		this.skill = skill;
		this.xp = xp;
		this.levelRequired = levelRequired;
		this.measured = measured;
		this.gives = gives;
		this.perKill = perKill;
		this.note = note;
		this.consumesItemId = consumesItemId;
	}

	static XpRate perKill(Skill skill, double xp, boolean measured, String note)
	{
		return new XpRate(skill, xp, 0, measured, null, true, note, -1);
	}

	XpRate consuming(int itemId)
	{
		return new XpRate(skill, xp, levelRequired, measured, gives, perKill, note, itemId);
	}

	XpRate withXp(double newXp, String newNote)
	{
		return new XpRate(skill, newXp, levelRequired, measured, gives, perKill, newNote, consumesItemId);
	}
}
