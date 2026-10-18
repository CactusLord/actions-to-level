package com.actionstolevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What appeared in and disappeared from the inventory between two ticks.
 */
final class InventoryDiff
{
	static final class Change
	{
		final int itemId;
		final int quantity;

		Change(int itemId, int quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
		}
	}

	static final InventoryDiff EMPTY = new InventoryDiff(Collections.emptyList(), Collections.emptyList());

	final List<Change> gained;
	final List<Change> lost;

	private InventoryDiff(List<Change> gained, List<Change> lost)
	{
		this.gained = gained;
		this.lost = lost;
	}

	boolean isEmpty()
	{
		return gained.isEmpty() && lost.isEmpty();
	}

	int kinds()
	{
		return gained.size() + lost.size();
	}

	/**
	 * @param before item id -> quantity, or null if unknown
	 * @param after  item id -> quantity
	 */
	static InventoryDiff between(Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		if (before == null || after == null)
		{
			return EMPTY;
		}

		final List<Change> gained = new ArrayList<>();
		final List<Change> lost = new ArrayList<>();
		final Set<Integer> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());

		for (int id : ids)
		{
			final int delta = after.getOrDefault(id, 0) - before.getOrDefault(id, 0);
			if (delta > 0)
			{
				gained.add(new Change(id, delta));
			}
			else if (delta < 0)
			{
				lost.add(new Change(id, -delta));
			}
		}

		return new InventoryDiff(gained, lost);
	}
}
