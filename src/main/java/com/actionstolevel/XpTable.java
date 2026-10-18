package com.actionstolevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Singleton;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;

/**
 * Lookups over the base XP values in {@link XpData}.
 */
@Singleton
class XpTable
{
	static final class Entry
	{
		final Skill skill;
		/** Item produced/used, or -1 for actions that aren't tied to an item (agility, thieving...). */
		final int itemId;
		final double xp;
		final int level;
		/** Name to show instead of the item's own name, or null. */
		final String nameOverride;
		/** Lower-case display name, filled in by {@link #buildNameIndex(ItemManager)}. */
		String name;

		Entry(XpData.Row row)
		{
			this.skill = row.skill;
			this.itemId = row.itemId;
			this.xp = row.xp;
			this.level = row.level;
			this.nameOverride = row.name;
		}

		boolean isNamedAction()
		{
			return itemId <= 0;
		}
	}

	private final Map<Integer, Map<Skill, Entry>> byItem = new HashMap<>();
	private final List<Entry> all = new ArrayList<>();
	/** Lower-case name -> entries. Built lazily on the client thread because item names need the cache. */
	private Map<String, List<Entry>> byName;

	XpTable()
	{
		for (XpData.Row row : XpData.ROWS)
		{
			final Entry entry = new Entry(row);
			all.add(entry);

			if (!entry.isNamedAction())
			{
				// Keep the first (lowest level) entry per item and skill
				byItem.computeIfAbsent(entry.itemId, k -> new EnumMap<>(Skill.class)).putIfAbsent(entry.skill, entry);
			}
		}
	}

	/** Entries for an exact item id, keyed by skill. */
	Map<Skill, Entry> forItem(int itemId)
	{
		final Map<Skill, Entry> entries = byItem.get(itemId);
		return entries == null ? Collections.emptyMap() : entries;
	}

	Entry forItem(int itemId, Skill skill)
	{
		return forItem(itemId).get(skill);
	}

	boolean isNameIndexBuilt()
	{
		return byName != null;
	}

	/**
	 * Resolves display names for every entry. Must run on the client thread.
	 */
	void buildNameIndex(ItemManager itemManager)
	{
		if (byName != null)
		{
			return;
		}

		final Map<String, List<Entry>> index = new HashMap<>();
		for (Entry entry : all)
		{
			String name = entry.nameOverride;
			if (name == null)
			{
				try
				{
					name = itemManager.getItemComposition(entry.itemId).getMembersName();
				}
				catch (RuntimeException e)
				{
					continue;
				}
			}

			if (name == null || name.isEmpty() || "null".equalsIgnoreCase(name))
			{
				continue;
			}

			entry.name = name.toLowerCase(Locale.ROOT);
			index.computeIfAbsent(entry.name, k -> new ArrayList<>(1)).add(entry);

			// Named actions such as "Man / Woman" or "Warrior Women / Al-Kharid Warrior"
			if (entry.isNamedAction() && entry.name.contains(" / "))
			{
				for (String part : entry.name.split(" / "))
				{
					index.computeIfAbsent(part.trim(), k -> new ArrayList<>(1)).add(entry);
				}
			}
		}

		byName = index;
	}

	/** Entries whose name matches exactly (case-insensitive). Empty until the name index is built. */
	List<Entry> forName(String name)
	{
		if (byName == null || name == null)
		{
			return Collections.emptyList();
		}
		final List<Entry> entries = byName.get(name.toLowerCase(Locale.ROOT));
		return entries == null ? Collections.emptyList() : entries;
	}

	/** First entry of the given skill whose name starts with the prefix (case-insensitive). */
	Entry findByPrefix(Skill skill, String prefix)
	{
		if (byName == null || prefix == null || prefix.isEmpty())
		{
			return null;
		}

		final String p = prefix.toLowerCase(Locale.ROOT);
		Entry best = null;
		for (Entry entry : all)
		{
			if (entry.skill != skill || entry.name == null)
			{
				continue;
			}
			if (entry.name.equals(p))
			{
				return entry;
			}
			if (best == null && entry.name.startsWith(p))
			{
				best = entry;
			}
		}
		return best;
	}
}
