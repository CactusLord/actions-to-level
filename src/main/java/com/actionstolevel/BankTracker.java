package com.actionstolevel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * What you own: the bank as last seen (remembered per account between sessions), plus
 * whatever you're carrying and wearing right now. Ironmen gather everything themselves,
 * so "have I got enough?" is the question that matters.
 */
final class BankTracker
{
	static final String BANK_KEY = "bank";
	/** Don't write the bank to disk more often than this while it's open. */
	private static final int SAVE_EVERY_TICKS = 10;

	private final Client client;
	private final ItemManager itemManager;
	private final ConfigManager configManager;

	private Map<Integer, Integer> bank = Collections.emptyMap();
	private boolean known;
	private boolean dirty;
	private String loadedProfile;
	private int ticksSinceSave;

	BankTracker(Client client, ItemManager itemManager, ConfigManager configManager)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.configManager = configManager;
	}

	/** Load the remembered bank for whichever account is logged in, if not already loaded. */
	void ensureLoaded()
	{
		final String profile = configManager.getRSProfileKey();
		if (profile == null || profile.equals(loadedProfile))
		{
			return;
		}
		loadedProfile = profile;
		final String saved = configManager.getRSProfileConfiguration(ActionsToLevelConfig.GROUP, BANK_KEY);
		bank = deserialize(saved);
		known = saved != null && !saved.isEmpty();
		dirty = false;
	}

	void reset()
	{
		save();
		bank = Collections.emptyMap();
		known = false;
		loadedProfile = null;
	}

	void onContainerChanged(int containerId, ItemContainer container)
	{
		if (containerId != InventoryID.BANK || container == null)
		{
			return;
		}
		ensureLoaded();
		bank = count(container);
		known = true;
		dirty = true;
	}

	void onTick()
	{
		if (dirty && ++ticksSinceSave >= SAVE_EVERY_TICKS)
		{
			save();
		}
	}

	void save()
	{
		ticksSinceSave = 0;
		if (!dirty || loadedProfile == null)
		{
			return;
		}
		configManager.setRSProfileConfiguration(ActionsToLevelConfig.GROUP, BANK_KEY, serialize(bank));
		dirty = false;
	}

	boolean bankKnown()
	{
		ensureLoaded();
		return known;
	}

	int inBank(int itemId)
	{
		ensureLoaded();
		return bank.getOrDefault(itemId, 0);
	}

	/** Inventory plus worn equipment, read live. */
	int carried(int itemId)
	{
		return count(client.getItemContainer(InventoryID.INV), itemId) + count(client.getItemContainer(InventoryID.WORN), itemId);
	}

	int total(int itemId)
	{
		return inBank(itemId) + carried(itemId);
	}

	Set<Integer> bankItemIds()
	{
		ensureLoaded();
		return Collections.unmodifiableSet(bank.keySet());
	}

	private Map<Integer, Integer> count(ItemContainer container)
	{
		final Map<Integer, Integer> counts = new HashMap<>();
		if (container == null)
		{
			return counts;
		}
		for (Item item : container.getItems())
		{
			// Placeholders have a quantity of 0
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				counts.merge(itemManager.canonicalize(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	private int count(ItemContainer container, int itemId)
	{
		if (container == null)
		{
			return 0;
		}
		int total = 0;
		for (Item item : container.getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0 && itemManager.canonicalize(item.getId()) == itemId)
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	static String serialize(Map<Integer, Integer> counts)
	{
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Integer> e : counts.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		return sb.toString();
	}

	static Map<Integer, Integer> deserialize(String s)
	{
		final Map<Integer, Integer> counts = new HashMap<>();
		if (s == null || s.isEmpty())
		{
			return counts;
		}
		for (String pair : s.split(","))
		{
			final int colon = pair.indexOf(':');
			if (colon <= 0)
			{
				continue;
			}
			try
			{
				final int id = Integer.parseInt(pair.substring(0, colon).trim());
				final int qty = Integer.parseInt(pair.substring(colon + 1).trim());
				if (id > 0 && qty > 0)
				{
					counts.put(id, qty);
				}
			}
			catch (NumberFormatException ignored)
			{
				// skip a corrupt entry
			}
		}
		return counts;
	}
}
