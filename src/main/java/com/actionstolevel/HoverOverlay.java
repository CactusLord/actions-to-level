package com.actionstolevel;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Shows the counts as a tooltip when you hover over an item, rock, tree or monster,
 * so you don't need to right-click.
 */
class HoverOverlay extends Overlay
{
	private static final Color STRONG = new Color(255, 200, 0);
	private static final Color WARN = new Color(255, 120, 90);
	private static final int MAX_LINES = 4;

	private final Client client;
	private final ActionsToLevelPlugin plugin;
	private final ActionsToLevelConfig config;
	private final TooltipManager tooltipManager;
	private final ItemManager itemManager;

	private String cachedKey;
	private int cachedTick = -1;
	private String cachedText;

	@Inject
	HoverOverlay(Client client, ActionsToLevelPlugin plugin, ActionsToLevelConfig config, TooltipManager tooltipManager, ItemManager itemManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.tooltipManager = tooltipManager;
		this.itemManager = itemManager;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if ((!config.tooltipItems() && !config.tooltipWorld()) || client.isMenuOpen())
		{
			return null;
		}

		final MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return null;
		}
		final MenuEntry entry = entries[entries.length - 1];
		final String key = keyFor(entry);
		if (key == null)
		{
			return null;
		}

		// Work it out at most once a game tick per thing hovered
		if (!key.equals(cachedKey) || cachedTick != client.getTickCount())
		{
			cachedKey = key;
			cachedTick = client.getTickCount();
			cachedText = build(entry);
		}

		if (cachedText != null && !cachedText.isEmpty())
		{
			tooltipManager.add(new Tooltip(cachedText));
		}
		return null;
	}

	private String keyFor(MenuEntry entry)
	{
		switch (entry.getType())
		{
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case EXAMINE_NPC:
				return config.tooltipWorld() && entry.getNpc() != null ? "npc:" + entry.getNpc().getIndex() : null;
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case EXAMINE_OBJECT:
				return config.tooltipWorld() ? "obj:" + entry.getTarget() : null;
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case EXAMINE_ITEM_GROUND:
				return config.tooltipItems() ? "ground:" + entry.getIdentifier() : null;
			default:
				final Widget w = entry.getWidget();
				return config.tooltipItems() && w != null && w.getItemId() > 0 ? "item:" + w.getItemId() : null;
		}
	}

	private String build(MenuEntry entry)
	{
		final List<Line> lines;
		switch (entry.getType())
		{
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case EXAMINE_NPC:
			{
				final NPC npc = entry.getNpc();
				final String name = CombatTracker.npcName(npc);
				lines = name == null ? Collections.emptyList() : plugin.reports().npc(npc, name, CombatTracker.isAttackable(npc), true);
				break;
			}
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case EXAMINE_OBJECT:
			{
				final String name = ActionsToLevelPlugin.cleanName(ActionsToLevelPlugin.afterArrow(entry.getTarget()));
				lines = name == null ? Collections.emptyList() : plugin.reports().object(name, true);
				break;
			}
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case EXAMINE_ITEM_GROUND:
				lines = plugin.reports().item(itemManager.canonicalize(entry.getIdentifier()), true);
				break;
			default:
			{
				final Widget w = entry.getWidget();
				lines = w == null ? Collections.emptyList() : plugin.reports().item(itemManager.canonicalize(w.getItemId()), true);
				break;
			}
		}

		final StringBuilder sb = new StringBuilder();
		int count = 0;
		for (Line line : lines)
		{
			if (count++ >= MAX_LINES)
			{
				break;
			}
			if (sb.length() > 0)
			{
				sb.append("</br>");
			}
			sb.append(line.tooltip(STRONG, WARN));
		}
		return sb.toString();
	}
}
