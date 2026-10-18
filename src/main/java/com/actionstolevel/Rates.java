package com.actionstolevel;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

/**
 * XP per action for items, objects and NPCs, from the base table, simple name rules and what
 * you've actually been getting. Also knows which items an action uses up, so the plugin can
 * count what you have.
 */
final class Rates
{
	/** "Raw shark" is cooked into "Shark", "Grimy ranarr weed" is cleaned into "Ranarr weed"... */
	private static final String[][] PRODUCT_PREFIXES = {
		{"raw ", Skill.COOKING.name()},
		{"grimy ", Skill.HERBLORE.name()},
		{"uncut ", Skill.CRAFTING.name()},
	};
	/** Skills whose table items are used up rather than made. */
	private static final Set<Skill> CONSUMING_SKILLS = EnumSet.of(Skill.FIREMAKING, Skill.PRAYER);
	/** Skills whose named actions are the actual NPC/object you click. */
	private static final Set<Skill> NAME_MATCHED_SKILLS = EnumSet.of(Skill.THIEVING, Skill.HUNTER);
	private static final String[] ROCK_SUFFIXES = {" rocks", " rock", " crystals", " vein", " deposit"};

	private final XpTable xpTable;
	private final LearnedRates learned;
	private final RecipeBook recipes;
	private final ItemManager itemManager;
	private final ActionsToLevelConfig config;
	private final Map<String, Integer> idByName = new LinkedHashMap<>();

	Rates(XpTable xpTable, LearnedRates learned, RecipeBook recipes, ItemManager itemManager, ActionsToLevelConfig config)
	{
		this.xpTable = xpTable;
		this.learned = learned;
		this.recipes = recipes;
		this.itemManager = itemManager;
		this.config = config;
	}

	/** Every skill this item gives XP in. */
	Map<Skill, XpRate> forItem(int itemId)
	{
		final Map<Skill, XpRate> rates = new EnumMap<>(Skill.class);
		for (XpTable.Entry e : xpTable.forItem(itemId).values())
		{
			final XpRate rate = new XpRate(e.skill, e.xp, e.level, false, null);
			rates.put(e.skill, CONSUMING_SKILLS.contains(e.skill) ? rate.consuming(itemId) : rate);
		}

		// Raw fish -> cooking, grimy herbs -> cleaning, uncut gems -> cutting
		final String lower = itemName(itemId).toLowerCase(Locale.ROOT);
		for (String[] prefix : PRODUCT_PREFIXES)
		{
			if (!lower.startsWith(prefix[0]))
			{
				continue;
			}
			final Skill skill = Skill.valueOf(prefix[1]);
			for (XpTable.Entry e : xpTable.forName(lower.substring(prefix[0].length())))
			{
				if (e.skill == skill)
				{
					rates.putIfAbsent(skill, new XpRate(skill, e.xp, e.level, false, null).consuming(itemId));
					break;
				}
			}
		}

		mergeLearned(LearnedRates.itemKey(itemId), rates);

		// Things you've seen this item used up for (an ingredient, or burned/buried on its own)
		for (Map.Entry<Skill, XpRate> e : rates.entrySet())
		{
			final XpRate rate = e.getValue();
			if (rate.consumesItemId < 0 && (recipes.isConsumed(itemId, e.getKey()) || !recipes.using(itemId, e.getKey()).isEmpty()))
			{
				e.setValue(rate.consuming(itemId));
			}
		}
		return rates;
	}

	/** Every skill an object or NPC (rock, tree, fishing spot, stall, pickpocket target) gives XP in. */
	Map<Skill, XpRate> forTarget(String name, boolean includeLearned)
	{
		final Map<Skill, XpRate> rates = new EnumMap<>(Skill.class);
		final String lower = name.toLowerCase(Locale.ROOT);

		// Pickpocket targets, stalls and hunter creatures listed by name. Farming/construction names like
		// "Yew tree" are patch/house builds, not the wild object, so those are left to learning.
		for (XpTable.Entry e : xpTable.forName(lower))
		{
			if (e.isNamedAction() && NAME_MATCHED_SKILLS.contains(e.skill))
			{
				rates.putIfAbsent(e.skill, new XpRate(e.skill, e.xp, e.level, false, null));
			}
		}

		// "Iron rocks" -> Iron ore, "Gem rocks", "Amethyst crystals"...
		for (String suffix : ROCK_SUFFIXES)
		{
			if (lower.endsWith(suffix) && lower.length() > suffix.length())
			{
				XpTable.Entry ore = firstOfSkill(xpTable.forName(lower), Skill.MINING);
				if (ore == null)
				{
					ore = xpTable.findByPrefix(Skill.MINING, lower.substring(0, lower.length() - suffix.length()));
				}
				addGives(rates, ore, name);
				break;
			}
		}

		// "Yew tree" -> Yew logs, plain "Tree" -> Logs
		if (lower.equals("tree") || lower.endsWith(" tree"))
		{
			final String kind = lower.equals("tree") ? "" : lower.substring(0, lower.length() - " tree".length());
			XpTable.Entry logs = kind.isEmpty() ? null : xpTable.findByPrefix(Skill.WOODCUTTING, kind + " logs");
			if (logs == null && !kind.isEmpty())
			{
				logs = xpTable.findByPrefix(Skill.WOODCUTTING, kind);
			}
			if (logs == null)
			{
				// Regular, dead, evergreen and jungle trees all give normal logs
				logs = firstOfSkill(xpTable.forName("logs"), Skill.WOODCUTTING);
			}
			addGives(rates, logs, name);
		}
		else if (!rates.containsKey(Skill.WOODCUTTING))
		{
			// Some trees are just called "Oak", "Willow" or "Yew"
			addGives(rates, firstOfSkill(xpTable.forName(lower + " logs"), Skill.WOODCUTTING), name);
		}

		if (includeLearned)
		{
			mergeLearned(LearnedRates.targetKey(name), rates);
		}
		return rates;
	}

	/**
	 * What one action of making this product uses: a learned recipe, or the obvious raw/grimy/uncut
	 * version for cooking, herb cleaning and gem cutting. Null when nothing is known.
	 */
	RecipeBook.Recipe recipeFor(int productId, Skill skill)
	{
		final RecipeBook.Recipe learnedRecipe = recipes.forProduct(productId, skill);
		if (learnedRecipe != null)
		{
			return learnedRecipe;
		}

		final String lower = itemName(productId).toLowerCase(Locale.ROOT);
		for (String[] prefix : PRODUCT_PREFIXES)
		{
			if (Skill.valueOf(prefix[1]) != skill)
			{
				continue;
			}
			final int input = findItemId(prefix[0] + lower);
			if (input > 0)
			{
				final Map<Integer, Double> one = new LinkedHashMap<>();
				one.put(input, 1.0);
				return new RecipeBook.Recipe(productId, skill, one);
			}
		}
		return null;
	}

	/** Recipes in this skill that use the item, learned only. */
	List<RecipeBook.Recipe> recipesUsing(int ingredientId, Skill skill)
	{
		return recipes.using(ingredientId, skill);
	}

	/** Gathering entry for an item you can't make (ore, logs, raw fish), or null. */
	XpTable.Entry gatheredBy(int itemId)
	{
		return xpTable.gatheredBy(itemId);
	}

	String itemName(int itemId)
	{
		if (itemId <= 0)
		{
			return "Unknown item";
		}
		final String name = itemManager.getItemComposition(itemId).getMembersName();
		return name == null || "null".equalsIgnoreCase(name) ? "Item " + itemId : name;
	}

	/** Item id for an exact (case-insensitive) tradeable item name, or -1. */
	int findItemId(String exactName)
	{
		final String key = exactName.toLowerCase(Locale.ROOT);
		final Integer cached = idByName.get(key);
		if (cached != null)
		{
			return cached;
		}
		int found = -1;
		try
		{
			for (ItemPrice price : itemManager.search(key))
			{
				if (price.getName() != null && price.getName().equalsIgnoreCase(exactName))
				{
					found = price.getId();
					break;
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// Prices not loaded yet
			return -1;
		}
		if (idByName.size() > 2000)
		{
			idByName.clear();
		}
		idByName.put(key, found);
		return found;
	}

	/** Tradeable items whose name contains the text, for search. */
	List<ItemPrice> searchItems(String text)
	{
		try
		{
			return itemManager.search(text);
		}
		catch (RuntimeException e)
		{
			return Collections.emptyList();
		}
	}

	/** Learned rate if it's been seen twice, else the table value, else what was just seen. */
	double bestRate(String key, Skill skill, XpTable.Entry base, double observed)
	{
		final LearnedRates.Rate rate = config.learnRates() ? learned.get(key, skill) : null;
		if (rate != null && rate.samples >= 2)
		{
			return rate.average();
		}
		if (base != null)
		{
			return base.xp;
		}
		if (rate != null)
		{
			return rate.average();
		}
		return observed;
	}

	private static XpTable.Entry firstOfSkill(List<XpTable.Entry> entries, Skill skill)
	{
		for (XpTable.Entry e : entries)
		{
			if (e.skill == skill)
			{
				return e;
			}
		}
		return null;
	}

	private void addGives(Map<Skill, XpRate> rates, XpTable.Entry entry, String targetName)
	{
		if (entry == null)
		{
			return;
		}

		String gives = entry.nameOverride != null ? entry.nameOverride : itemName(entry.itemId);
		if (gives.equalsIgnoreCase(targetName))
		{
			// "Gem rocks (Gem rocks)" says nothing new
			gives = null;
		}
		rates.putIfAbsent(entry.skill, new XpRate(entry.skill, entry.xp, entry.level, false, gives));
	}

	/** Swap in what you've actually been getting once it has been seen twice. */
	private void mergeLearned(String key, Map<Skill, XpRate> rates)
	{
		if (!config.learnRates())
		{
			return;
		}
		for (Map.Entry<Skill, LearnedRates.Rate> e : learned.get(key).entrySet())
		{
			final XpRate base = rates.get(e.getKey());
			final LearnedRates.Rate rate = e.getValue();
			if (base == null || rate.samples >= 2)
			{
				XpRate merged = new XpRate(e.getKey(), rate.average(),
					base != null ? base.levelRequired : 0, true, base != null ? base.gives : null);
				if (base != null && base.consumesItemId > 0)
				{
					merged = merged.consuming(base.consumesItemId);
				}
				rates.put(e.getKey(), merged);
			}
		}
	}
}
