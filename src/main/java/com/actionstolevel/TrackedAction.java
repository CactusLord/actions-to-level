package com.actionstolevel;

import java.time.Instant;
import net.runelite.api.Skill;

/**
 * One line on the overlay: an item (or object/NPC) you've recently gained XP from.
 * The "how many left" number is worked out fresh from your current XP each frame.
 */
final class TrackedAction
{
	final Skill skill;
	final String name;
	double xpEach;
	int levelRequired;
	Instant lastUpdated;

	TrackedAction(Skill skill, String name)
	{
		this.skill = skill;
		this.name = name;
	}

	static String key(Skill skill, String name)
	{
		return skill.name() + '|' + name;
	}
}
