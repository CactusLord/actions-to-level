package com.actionstolevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * Recipes learned by watching your inventory: when an XP drop comes with something made and
 * something used up, that's a recipe (1 yew longbow (u) + 1 bow string makes 1 yew longbow).
 * Also remembers items that are used up on their own (burning logs, burying bones).
 */
final class RecipeBook
{
	static final String RECIPES_KEY = "recipes";
	/** Ignore ticks where many kinds of item changed; that's banking, not crafting. */
	private static final int MAX_INGREDIENT_KINDS = 4;

	static final class Recipe
	{
		final int productId;
		final Skill skill;
		/** Ingredient item id -> how many are used per product made. */
		final Map<Integer, Double> perProduct;

		Recipe(int productId, Skill skill, Map<Integer, Double> perProduct)
		{
			this.productId = productId;
			this.skill = skill;
			this.perProduct = Collections.unmodifiableMap(perProduct);
		}
	}

	private final Map<String, Recipe> recipes = new HashMap<>();
	/** "itemId|SKILL" for items that are used up by themselves for XP. */
	private final Set<String> consumed = new HashSet<>();
	private boolean dirty;

	private static String key(int itemId, Skill skill)
	{
		return itemId + "|" + skill.name();
	}

	/**
	 * Learn from one XP drop.
	 *
	 * @param product the item made, or null if nothing was made
	 */
	void learn(Skill skill, InventoryDiff diff, InventoryDiff.Change product)
	{
		if (diff.isEmpty() || diff.kinds() > MAX_INGREDIENT_KINDS)
		{
			return;
		}

		if (product == null || !diff.gained.contains(product))
		{
			// Nothing made: a single item used up on its own (logs burned, bones buried)
			if (diff.gained.isEmpty() && diff.lost.size() == 1 && consumed.add(key(diff.lost.get(0).itemId, skill)))
			{
				dirty = true;
			}
			return;
		}

		if (diff.lost.isEmpty())
		{
			return;
		}

		final Map<Integer, Double> perProduct = new LinkedHashMap<>();
		for (InventoryDiff.Change used : diff.lost)
		{
			perProduct.put(used.itemId, (double) used.quantity / product.quantity);
		}

		final Recipe known = recipes.get(key(product.itemId, skill));
		if (known == null || !known.perProduct.equals(perProduct))
		{
			recipes.put(key(product.itemId, skill), new Recipe(product.itemId, skill, perProduct));
			dirty = true;
		}
	}

	Recipe forProduct(int productId, Skill skill)
	{
		return recipes.get(key(productId, skill));
	}

	/** Any skill's recipe for this product. */
	Recipe forProduct(int productId)
	{
		for (Recipe r : recipes.values())
		{
			if (r.productId == productId)
			{
				return r;
			}
		}
		return null;
	}

	/** Recipes in this skill that use the item as an ingredient. */
	List<Recipe> using(int ingredientId, Skill skill)
	{
		final List<Recipe> out = new ArrayList<>();
		for (Recipe r : recipes.values())
		{
			if (r.skill == skill && r.perProduct.containsKey(ingredientId))
			{
				out.add(r);
			}
		}
		return out;
	}

	boolean isConsumed(int itemId, Skill skill)
	{
		return consumed.contains(key(itemId, skill));
	}

	boolean isDirty()
	{
		return dirty;
	}

	void clear()
	{
		recipes.clear();
		consumed.clear();
		dirty = true;
	}

	/** One recipe per line: "product|SKILL|id:qty;id:qty", consumed items as "id|SKILL|-". */
	String serialize()
	{
		final StringBuilder sb = new StringBuilder();
		for (Recipe r : recipes.values())
		{
			sb.append(r.productId).append('|').append(r.skill.name()).append('|');
			boolean first = true;
			for (Map.Entry<Integer, Double> e : r.perProduct.entrySet())
			{
				if (!first)
				{
					sb.append(';');
				}
				first = false;
				sb.append(e.getKey()).append(':').append(String.format(Locale.ROOT, "%.4f", e.getValue()));
			}
			sb.append('\n');
		}
		for (String c : consumed)
		{
			sb.append(c).append("|-\n");
		}
		dirty = false;
		return sb.toString();
	}

	void deserialize(String data)
	{
		recipes.clear();
		consumed.clear();
		dirty = false;
		if (data == null || data.isEmpty())
		{
			return;
		}
		for (String line : data.split("\n"))
		{
			final String[] parts = line.split("\\|");
			if (parts.length != 3)
			{
				continue;
			}
			try
			{
				final int itemId = Integer.parseInt(parts[0]);
				final Skill skill = Skill.valueOf(parts[1]);
				if ("-".equals(parts[2]))
				{
					consumed.add(key(itemId, skill));
					continue;
				}
				final Map<Integer, Double> perProduct = new LinkedHashMap<>();
				for (String ing : parts[2].split(";"))
				{
					final String[] kv = ing.split(":");
					perProduct.put(Integer.parseInt(kv[0]), Double.parseDouble(kv[1]));
				}
				if (!perProduct.isEmpty())
				{
					recipes.put(key(itemId, skill), new Recipe(itemId, skill, perProduct));
				}
			}
			catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ignored)
			{
				// skip a corrupt line
			}
		}
	}
}
