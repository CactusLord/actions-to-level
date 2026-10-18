package com.actionstolevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;

/**
 * Your goals, e.g. "70 Fletching making yew longbows" or "61 Slayer killing hellhounds".
 * Kept per account. Each goal works out what it needs from your bank and what's missing.
 */
final class Goals
{
	static final String GOALS_KEY = "goals";
	static final int MAX_GOALS = 12;

	static final class Goal
	{
		enum Kind
		{
			/** Making, using or gathering an item. */
			ITEM,
			/** A named action with no item (agility course, pickpocketing, a stall). */
			ACTION,
			/** Killing a monster. */
			MONSTER
		}

		final long id;
		final Skill skill;
		final Kind kind;
		final int itemId;
		final String name;
		int targetLevel;
		boolean reached;

		Goal(long id, Skill skill, Kind kind, int itemId, String name, int targetLevel)
		{
			this.id = id;
			this.skill = skill;
			this.kind = kind;
			this.itemId = itemId;
			this.name = name;
			this.targetLevel = targetLevel;
		}

		boolean sameMethod(Goal o)
		{
			return skill == o.skill && kind == o.kind && itemId == o.itemId && name.equalsIgnoreCase(o.name);
		}
	}

	private final ConfigManager configManager;
	private final List<Goal> goals = new ArrayList<>();
	private String loadedProfile;
	private long nextId = 1;

	Goals(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	void ensureLoaded()
	{
		final String profile = configManager.getRSProfileKey();
		if (profile == null || profile.equals(loadedProfile))
		{
			return;
		}
		loadedProfile = profile;
		goals.clear();
		deserialize(configManager.getRSProfileConfiguration(ActionsToLevelConfig.GROUP, GOALS_KEY));
	}

	void reset()
	{
		goals.clear();
		loadedProfile = null;
	}

	List<Goal> all()
	{
		ensureLoaded();
		return Collections.unmodifiableList(goals);
	}

	/** Add a goal, or update the target of an existing goal for the same skill and method. */
	Goal add(Skill skill, Goal.Kind kind, int itemId, String name, int targetLevel)
	{
		ensureLoaded();
		final Goal goal = new Goal(nextId++, skill, kind, itemId, name, targetLevel);
		for (Goal g : goals)
		{
			if (g.sameMethod(goal))
			{
				g.targetLevel = targetLevel;
				g.reached = false;
				save();
				return g;
			}
		}
		if (goals.size() >= MAX_GOALS)
		{
			goals.remove(0);
		}
		goals.add(goal);
		save();
		return goal;
	}

	void remove(long id)
	{
		ensureLoaded();
		if (goals.removeIf(g -> g.id == id))
		{
			save();
		}
	}

	void setTarget(long id, int level)
	{
		ensureLoaded();
		for (Goal g : goals)
		{
			if (g.id == id && g.targetLevel != level)
			{
				g.targetLevel = level;
				g.reached = false;
				save();
			}
		}
	}

	void markReached(Goal goal)
	{
		goal.reached = true;
		save();
	}

	private void save()
	{
		if (loadedProfile != null)
		{
			configManager.setRSProfileConfiguration(ActionsToLevelConfig.GROUP, GOALS_KEY, serialize());
		}
	}

	/** One goal per line: "SKILL|KIND|itemId|target|reached|name". */
	String serialize()
	{
		final StringBuilder sb = new StringBuilder();
		for (Goal g : goals)
		{
			sb.append(g.skill.name()).append('|')
				.append(g.kind.name()).append('|')
				.append(g.itemId).append('|')
				.append(g.targetLevel).append('|')
				.append(g.reached ? 1 : 0).append('|')
				.append(g.name.replace('|', ' ').replace('\n', ' '))
				.append('\n');
		}
		return sb.toString();
	}

	void deserialize(String data)
	{
		if (data == null || data.isEmpty())
		{
			return;
		}
		for (String line : data.split("\n"))
		{
			final String[] p = line.split("\\|", 6);
			if (p.length != 6)
			{
				continue;
			}
			try
			{
				final Goal g = new Goal(nextId++, Skill.valueOf(p[0].trim().toUpperCase(Locale.ROOT)),
					Goal.Kind.valueOf(p[1].trim()), Integer.parseInt(p[2].trim()), p[5], Integer.parseInt(p[3].trim()));
				g.reached = "1".equals(p[4].trim());
				goals.add(g);
			}
			catch (IllegalArgumentException ignored)
			{
				// skip a corrupt line
			}
		}
	}
}
