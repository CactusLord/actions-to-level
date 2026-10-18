package com.actionstolevel;

import com.google.inject.Provides;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NPCManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Actions To Level",
	description = "Shows how many more items, actions or kills you need to level up",
	tags = {"xp", "level", "skilling", "actions", "calculator", "goal", "kills", "combat", "slayer", "mining", "woodcutting", "fishing"}
)
@PluginDependency(SlayerPlugin.class)
public class ActionsToLevelPlugin extends Plugin
{
	static final String MENU_OPTION = "Actions to level";

	/** An XP drop is credited to the last clicked object/NPC for this many ticks (~5 minutes). */
	private static final int TARGET_MEMORY_TICKS = 500;
	/** Ignore the XP sync the server sends while logging in / hopping. */
	private static final int LOGIN_GRACE_TICKS = 2;
	private static final int SAVE_INTERVAL_TICKS = 100;
	/** Don't learn from ticks where lots of different items moved (banking, etc.). */
	private static final int MAX_ITEM_KINDS_TO_LEARN = 4;

	/** "Raw shark" is cooked into "Shark", "Grimy ranarr weed" is cleaned into "Ranarr weed"... */
	private static final String[][] PRODUCT_PREFIXES = {
		{"raw ", Skill.COOKING.name()},
		{"grimy ", Skill.HERBLORE.name()},
		{"uncut ", Skill.CRAFTING.name()},
	};
	/** Skills whose named actions are the actual NPC/object you click. */
	private static final Set<Skill> NAME_MATCHED_SKILLS = EnumSet.of(Skill.THIEVING, Skill.HUNTER);
	private static final String[] ROCK_SUFFIXES = {" rocks", " rock", " crystals", " vein", " deposit"};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ActionsToLevelConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ActionsToLevelOverlay overlay;

	@Inject
	private XpTable xpTable;

	@Inject
	private NPCManager npcManager;

	@Inject
	private SlayerPluginService slayerService;

	private final LearnedRates learned = new LearnedRates();
	private CombatTracker combat;
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> pendingXp = new EnumMap<>(Skill.class);
	/** Most recently updated entry last. Only touched on the client thread. */
	private final LinkedHashMap<String, TrackedAction> tracked = new LinkedHashMap<>();
	private final DecimalFormat xpFormat = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

	/** Item id -> quantity at the end of the last processed tick, or null when unknown. */
	private Map<Integer, Integer> inventorySnapshot;
	private boolean pendingWaitedATick;
	private int loginGraceTicks;
	private String lastTarget;
	private int lastTargetTick;
	private int ticksSinceSave;

	@Provides
	ActionsToLevelConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ActionsToLevelConfig.class);
	}

	@Override
	protected void startUp()
	{
		learned.deserialize(configManager.getConfiguration(ActionsToLevelConfig.GROUP, ActionsToLevelConfig.LEARNED_RATES_KEY));
		combat = new CombatTracker(client, npcManager, slayerService, learned);
		overlayManager.add(overlay);
		resetSession();

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::seedFromClient);
		}
	}

	@Override
	protected void shutDown()
	{
		saveLearned();
		overlayManager.remove(overlay);
		tracked.clear();
		resetSession();
	}

	private void resetSession()
	{
		lastXp.clear();
		pendingXp.clear();
		inventorySnapshot = null;
		pendingWaitedATick = false;
		loginGraceTicks = 0;
		lastTarget = null;
		if (combat != null)
		{
			combat.reset();
		}
	}

	/** Take a fresh baseline of XP and inventory so the next XP drop is measured correctly. */
	private void seedFromClient()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		lastXp.clear();
		for (Skill skill : Skill.values())
		{
			lastXp.put(skill, client.getSkillExperience(skill));
		}
		pendingXp.clear();
		pendingWaitedATick = false;
		inventorySnapshot = readInventory();
		xpTable.buildNameIndex(itemManager);
	}

	// ------------------------------------------------------------------
	// Automatic tracking: XP drop -> which item / object caused it
	// ------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGING_IN:
			case HOPPING:
				resetSession();
				loginGraceTicks = LOGIN_GRACE_TICKS;
				break;
			case LOGIN_SCREEN:
				saveLearned();
				resetSession();
				tracked.clear();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		final Skill skill = event.getSkill();
		final Integer previous = lastXp.put(skill, event.getXp());

		if (previous == null || loginGraceTicks > 0)
		{
			return;
		}

		final int gained = event.getXp() - previous;
		if (gained <= 0)
		{
			return;
		}

		// Combat XP (and Magic while fighting) is counted per kill instead
		if (combat.onXp(skill, gained, client.getTickCount()))
		{
			return;
		}

		pendingXp.merge(skill, gained, Integer::sum);
	}

	// ------------------------------------------------------------------
	// Combat: kills -> XP per kill
	// ------------------------------------------------------------------

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() instanceof NPC && event.getHitsplat().isMine())
		{
			combat.onMyHitsplat((NPC) event.getActor(), client.getTickCount());
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() instanceof NPC)
		{
			combat.onNpcDeath((NPC) event.getActor(), client.getTickCount());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		final NPC npc = event.getNpc();
		if (npc.isDead())
		{
			// In case the death itself wasn't seen
			combat.onNpcDeath(npc, client.getTickCount());
		}
		else
		{
			combat.onNpcDespawned(npc);
		}
	}

	private void onKill(CombatTracker.Kill kill)
	{
		final Integer hp = combat.hitpoints(null, kill.name);
		final Map<Skill, XpRate> rates = combat.rates(kill.name, hp, kill.style, kill.observed);
		final List<XpRate> chatLines = new ArrayList<>();

		// Tracked oldest-first, so the style's own skills end up on top of the overlay
		if (config.showSlayer() && combat.gaveSlayerXp(kill.name))
		{
			final XpRate slayer = combat.slayerRate(kill.name, hp, combat.slayerNote(kill.name));
			if (slayer != null)
			{
				trackKill(kill.name, slayer);
				chatLines.add(slayer);
			}
		}

		final XpRate hitpoints = rates.get(Skill.HITPOINTS);
		if (hitpoints != null && config.showHitpoints())
		{
			trackKill(kill.name, hitpoints);
		}

		for (XpRate rate : rates.values())
		{
			if (rate.skill != Skill.HITPOINTS)
			{
				trackKill(kill.name, rate);
				chatLines.add(0, rate);
			}
		}

		if (config.chatOnXpDrop())
		{
			for (XpRate rate : chatLines)
			{
				sendChat(describeKills(kill.name, rate));
			}
		}
	}

	private void trackKill(String name, XpRate rate)
	{
		final TrackedAction action = track(rate.skill, name, rate.xp, 0);
		action.perKill = true;
		action.note = rate.note;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final MenuEntry entry = event.getMenuEntry();
		switch (entry.getType())
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
				rememberTarget(cleanName(afterArrow(entry.getTarget())));
				break;
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case WIDGET_TARGET_ON_NPC:
				rememberTarget(npcName(entry));
				break;
			case WIDGET_TARGET_ON_WIDGET:
				// Item on item, or a spell on an item (fletching, alching...) - not an object/NPC action
				lastTarget = null;
				break;
			case CC_OP:
			case CC_OP_LOW_PRIORITY:
				if (entry.getParam1() == InterfaceID.Inventory.ITEMS)
				{
					// Bury, drop, eat... - also not an object/NPC action
					lastTarget = null;
				}
				break;
			default:
				break;
		}
	}

	private void rememberTarget(String name)
	{
		if (name != null)
		{
			lastTarget = name;
			lastTargetTick = client.getTickCount();
		}
	}

	private String recentTarget()
	{
		if (lastTarget == null || client.getTickCount() - lastTargetTick > TARGET_MEMORY_TICKS)
		{
			return null;
		}
		return lastTarget;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (loginGraceTicks > 0)
		{
			if (--loginGraceTicks == 0)
			{
				seedFromClient();
			}
			return;
		}

		final CombatTracker.Kill kill = combat.onTick(client.getTickCount());
		if (kill != null && config.trackKills())
		{
			onKill(kill);
		}

		final Map<Integer, Integer> inventory = readInventory();
		boolean waiting = false;

		if (!pendingXp.isEmpty())
		{
			final InventoryDiff diff = InventoryDiff.between(inventorySnapshot, inventory);
			if (diff.isEmpty() && !pendingWaitedATick && inventorySnapshot != null)
			{
				// The item usually arrives on the same tick as the XP, but give it one more tick just in case
				pendingWaitedATick = true;
				waiting = true;
			}
			else
			{
				for (Map.Entry<Skill, Integer> drop : pendingXp.entrySet())
				{
					onXpDrop(drop.getKey(), drop.getValue(), diff);
				}
				pendingXp.clear();
				pendingWaitedATick = false;
			}
		}

		if (!waiting)
		{
			inventorySnapshot = inventory;
		}

		expireTracked();

		if (learned.isDirty() && ++ticksSinceSave >= SAVE_INTERVAL_TICKS)
		{
			saveLearned();
		}
	}

	private void onXpDrop(Skill skill, int xp, InventoryDiff diff)
	{
		final InventoryDiff.Change primary = pickPrimaryItem(skill, diff);
		final String target = recentTarget();
		final String alchemy = skill == Skill.MAGIC ? alchemyName(diff, xp) : null;

		// Alching isn't "XP per coin", so don't learn from it
		if (config.learnRates() && alchemy == null)
		{
			learnFrom(skill, xp, diff, primary, target);
		}

		final String name;
		final double xpEach;
		final int levelRequired;

		if (alchemy != null)
		{
			name = alchemy;
			xpEach = xp;
			levelRequired = 0;
		}
		else if (primary != null)
		{
			final XpTable.Entry base = xpTable.forItem(primary.itemId, skill);
			name = itemName(primary.itemId);
			xpEach = bestRate(LearnedRates.itemKey(primary.itemId), skill, base, (double) xp / primary.quantity);
			levelRequired = base != null ? base.level : 0;
		}
		else if (target != null)
		{
			final XpRate base = ratesForTarget(target, false).get(skill);
			// "Iron rocks" with a full inventory still counts as Iron ore, so it shares the same overlay line
			name = base != null && base.gives != null ? base.gives : target;
			final LearnedRates.Rate rate = config.learnRates() ? learned.get(LearnedRates.targetKey(target), skill) : null;
			if (rate != null && rate.samples >= 2)
			{
				xpEach = rate.average();
			}
			else if (base != null)
			{
				xpEach = base.xp;
			}
			else
			{
				xpEach = rate != null ? rate.average() : xp;
			}
			levelRequired = base != null ? base.levelRequired : 0;
		}
		else
		{
			name = skill.getName();
			xpEach = xp;
			levelRequired = 0;
		}

		final TrackedAction action = track(skill, name, xpEach, levelRequired);
		if (config.chatOnXpDrop())
		{
			sendChat(describe(action.name, null, skill, xpEach, levelRequired, false));
		}
	}

	/** Alching turns an item into coins using a nature rune; name it for what it is, not "Coins". */
	private static String alchemyName(InventoryDiff diff, int xp)
	{
		boolean coins = false;
		boolean nature = false;
		for (InventoryDiff.Change c : diff.gained)
		{
			coins |= c.itemId == ItemID.COINS;
		}
		for (InventoryDiff.Change c : diff.lost)
		{
			nature |= c.itemId == ItemID.NATURERUNE;
		}
		if (!coins || !nature)
		{
			return null;
		}
		return xp >= 65 ? "High Level Alchemy" : "Low Level Alchemy";
	}

	/**
	 * Work out which item the XP was for: something you just gained (ore, logs, fish, a cooked item)
	 * or something you just used up (bones, logs you burned, bars you smithed).
	 */
	private InventoryDiff.Change pickPrimaryItem(Skill skill, InventoryDiff diff)
	{
		for (InventoryDiff.Change c : diff.gained)
		{
			if (xpTable.forItem(c.itemId, skill) != null)
			{
				return c;
			}
		}
		for (InventoryDiff.Change c : diff.lost)
		{
			if (xpTable.forItem(c.itemId, skill) != null)
			{
				return c;
			}
		}
		for (InventoryDiff.Change c : diff.gained)
		{
			if (learned.get(LearnedRates.itemKey(c.itemId), skill) != null)
			{
				return c;
			}
		}
		for (InventoryDiff.Change c : diff.lost)
		{
			if (learned.get(LearnedRates.itemKey(c.itemId), skill) != null)
			{
				return c;
			}
		}
		if (diff.gained.size() == 1)
		{
			return diff.gained.get(0);
		}
		if (diff.gained.isEmpty() && diff.lost.size() == 1)
		{
			return diff.lost.get(0);
		}
		return null;
	}

	private void learnFrom(Skill skill, int xp, InventoryDiff diff, InventoryDiff.Change primary, String target)
	{
		if (primary != null && diff.kinds() <= MAX_ITEM_KINDS_TO_LEARN)
		{
			learned.record(LearnedRates.itemKey(primary.itemId), skill, (double) xp / primary.quantity);

			// Ingredients you used up, so right-clicking them later works too (raw fish, bars, logs...)
			for (InventoryDiff.Change used : diff.lost)
			{
				if (used != primary)
				{
					learned.record(LearnedRates.itemKey(used.itemId), skill, (double) xp / used.quantity);
				}
			}
		}

		if (target != null)
		{
			learned.record(LearnedRates.targetKey(target), skill, xp);
		}
	}

	private double bestRate(String key, Skill skill, XpTable.Entry base, double observed)
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

	private TrackedAction track(Skill skill, String name, double xpEach, int levelRequired)
	{
		final String key = TrackedAction.key(skill, name);
		TrackedAction action = tracked.remove(key);
		if (action == null)
		{
			action = new TrackedAction(skill, name);
		}
		action.xpEach = xpEach;
		action.levelRequired = levelRequired;
		action.perKill = false;
		action.note = null;
		action.lastUpdated = Instant.now();
		tracked.put(key, action);

		final Iterator<TrackedAction> it = tracked.values().iterator();
		while (tracked.size() > Math.max(1, config.maxEntries()) && it.hasNext())
		{
			it.next();
			it.remove();
		}
		return action;
	}

	private void expireTracked()
	{
		final int minutes = config.overlayTimeout();
		if (minutes <= 0 || tracked.isEmpty())
		{
			return;
		}
		final Instant cutoff = Instant.now().minus(Duration.ofMinutes(minutes));
		tracked.values().removeIf(a -> a.lastUpdated.isBefore(cutoff));
	}

	/** Newest first, limited to the configured count. Used by the overlay. */
	List<TrackedAction> getTrackedNewestFirst()
	{
		if (tracked.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<TrackedAction> list = new ArrayList<>(tracked.values());
		Collections.reverse(list);
		final int max = Math.max(1, config.maxEntries());
		return list.size() > max ? list.subList(0, max) : list;
	}

	void clearTracked()
	{
		tracked.clear();
	}

	LevelMath.Progress progressFor(Skill skill, double xpEach)
	{
		return LevelMath.progress(client.getSkillExperience(skill), config.targetLevel(), config.virtualLevels(), xpEach);
	}

	// ------------------------------------------------------------------
	// Right-click "Actions to level"
	// ------------------------------------------------------------------

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (config.requireShift() && !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		xpTable.buildNameIndex(itemManager);

		final MenuEntry[] entries = event.getMenuEntries();
		for (int idx = entries.length - 1; idx >= 0; --idx)
		{
			final MenuEntry entry = entries[idx];
			if (!"Examine".equals(entry.getOption()))
			{
				continue;
			}

			final Runnable onClick;
			final boolean known;

			switch (entry.getType())
			{
				case EXAMINE_OBJECT:
				{
					if (!config.menuOnObjects())
					{
						continue;
					}
					final String name = cleanName(entry.getTarget());
					if (name == null)
					{
						continue;
					}
					known = !ratesForTarget(name, true).isEmpty();
					onClick = () -> report(name, ratesForTarget(name, true));
					break;
				}
				case EXAMINE_NPC:
				{
					if (!config.menuOnNpcs())
					{
						continue;
					}
					final NPC npc = entry.getNpc();
					final String name = npc != null ? CombatTracker.npcName(npc) : npcName(entry);
					if (name == null)
					{
						continue;
					}
					final boolean attackable = CombatTracker.isAttackable(npc);
					known = attackable || !ratesForTarget(name, true).isEmpty();
					onClick = () -> reportNpc(npc, name, attackable);
					break;
				}
				case EXAMINE_ITEM_GROUND:
				case CC_OP:
				case CC_OP_LOW_PRIORITY:
				{
					if (!config.menuOnItems())
					{
						continue;
					}
					final int itemId = itemIdOf(entry);
					if (itemId <= 0)
					{
						continue;
					}
					known = !ratesForItem(itemId).isEmpty();
					onClick = () -> report(itemName(itemId), ratesForItem(itemId));
					break;
				}
				default:
					continue;
			}

			if (!known && config.onlyWhenKnown())
			{
				continue;
			}

			// Inserting at the Examine entry's index puts our option directly below Examine
			client.getMenu().createMenuEntry(idx)
				.setOption(MENU_OPTION)
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> onClick.run());
		}
	}

	private int itemIdOf(MenuEntry entry)
	{
		if (entry.getType() == MenuAction.EXAMINE_ITEM_GROUND)
		{
			return itemManager.canonicalize(entry.getIdentifier());
		}

		int itemId = entry.getItemId();
		if (itemId <= 0)
		{
			final Widget widget = entry.getWidget();
			itemId = widget != null ? widget.getItemId() : -1;
		}
		return itemId > 0 ? itemManager.canonicalize(itemId) : -1;
	}

	/** Every skill this item gives XP in. */
	private Map<Skill, XpRate> ratesForItem(int itemId)
	{
		final Map<Skill, XpRate> rates = new EnumMap<>(Skill.class);
		for (XpTable.Entry e : xpTable.forItem(itemId).values())
		{
			rates.put(e.skill, new XpRate(e.skill, e.xp, e.level, false, null));
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
					rates.putIfAbsent(skill, new XpRate(skill, e.xp, e.level, false, null));
					break;
				}
			}
		}

		mergeLearned(LearnedRates.itemKey(itemId), rates);
		return rates;
	}

	/** Every skill an object or NPC (rock, tree, fishing spot, stall, pickpocket target) gives XP in. */
	private Map<Skill, XpRate> ratesForTarget(String name, boolean includeLearned)
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
				rates.put(e.getKey(), new XpRate(e.getKey(), rate.average(),
					base != null ? base.levelRequired : 0, true, base != null ? base.gives : null));
			}
		}
	}

	private void report(String name, Map<Skill, XpRate> rates)
	{
		if (rates.isEmpty())
		{
			sendChat(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT).append(name)
				.append(ChatColorType.NORMAL).append(": no XP info yet. Do it once and the plugin will learn it.")
				.build());
			return;
		}

		for (XpRate rate : rates.values())
		{
			sendChat(describe(name, rate.gives, rate.skill, rate.xp, rate.levelRequired, rate.measured));
		}
	}

	/**
	 * Right-click on an NPC: thieving/hunter/fishing-spot info plus, for monsters,
	 * kills to level with your current attack style and kills to your next Slayer level.
	 */
	private void reportNpc(NPC npc, String name, boolean attackable)
	{
		final Map<Skill, XpRate> skilling = ratesForTarget(name, true);
		for (XpRate rate : skilling.values())
		{
			sendChat(describe(name, rate.gives, rate.skill, rate.xp, rate.levelRequired, rate.measured));
		}

		if (!attackable)
		{
			if (skilling.isEmpty())
			{
				report(name, skilling);
			}
			return;
		}

		final CombatStyle style = combat.currentStyle();
		final Integer hp = combat.hitpoints(npc, name);
		final Map<Skill, XpRate> rates = combat.rates(name, hp, style, null);

		final ChatMessageBuilder header = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT).append(name)
			.append(ChatColorType.NORMAL).append(hp != null ? " (" + hp + " hitpoints)" : "");
		if (!style.isKnown())
		{
			header.append(": couldn't read your attack style. Open the combat tab once and try again.");
		}
		else if (rates.isEmpty())
		{
			header.append(", " + style.displayName + ": hitpoints unknown. Kill one and the plugin will learn its XP.");
		}
		else
		{
			header.append(", " + style.displayName + ":");
		}
		sendChat(header.build());

		// Style skills first, then Hitpoints
		for (XpRate rate : rates.values())
		{
			if (rate.skill != Skill.HITPOINTS)
			{
				sendChat(describeKills(null, rate));
			}
		}
		final XpRate hitpoints = rates.get(Skill.HITPOINTS);
		if (hitpoints != null)
		{
			sendChat(describeKills(null, hitpoints));
		}

		if (config.showSlayer())
		{
			final XpRate slayer = combat.slayerRate(name, hp, combat.slayerNote(npc, name));
			if (slayer != null)
			{
				sendChat(describeKills(null, slayer));
			}
		}
	}

	/** e.g. "Strength: 87 kills to 71 (600 xp each)" or "Abyssal demon: 87 kills to Strength 71 (...)" */
	private String describeKills(String name, XpRate rate)
	{
		final Skill skill = rate.skill;
		final LevelMath.Progress p = progressFor(skill, rate.xp);
		final ChatMessageBuilder msg = new ChatMessageBuilder();
		if (name != null)
		{
			msg.append(ChatColorType.HIGHLIGHT).append(name).append(ChatColorType.NORMAL).append(": ");
		}
		else
		{
			msg.append("  " + skill.getName() + ": ");
		}

		final String skillPart = name != null ? skill.getName() + " " : "";
		if (p.isMaxed())
		{
			msg.append(name != null ? skill.getName() + " is maxed" : "maxed");
		}
		else if (p.actionsRemaining < 0)
		{
			msg.append(String.format(Locale.ROOT, "%,d xp to %s%d", p.xpRemaining, skillPart, p.targetLevel));
		}
		else
		{
			msg.append(ChatColorType.HIGHLIGHT)
				.append(String.format(Locale.ROOT, "%,d %s", p.actionsRemaining, p.actionsRemaining == 1 ? "kill" : "kills"))
				.append(ChatColorType.NORMAL)
				.append(" to " + skillPart + p.targetLevel
					+ " (" + xpFormat.format(rate.xp) + " xp each" + (rate.measured ? ", measured" : "") + ")");
		}

		if (rate.note != null)
		{
			msg.append(" - " + rate.note);
		}
		return msg.build();
	}

	/** e.g. "Iron ore: 112 more to Mining 42 (35 xp each)" */
	private String describe(String name, String gives, Skill skill, double xpEach, int levelRequired, boolean measured)
	{
		final LevelMath.Progress p = progressFor(skill, xpEach);
		final ChatMessageBuilder msg = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT).append(name)
			.append(ChatColorType.NORMAL).append(gives != null ? " (" + gives + "): " : ": ");

		if (p.isMaxed())
		{
			msg.append(skill.getName() + " is maxed");
		}
		else if (p.actionsRemaining < 0)
		{
			msg.append(String.format(Locale.ROOT, "%,d xp to %s %d", p.xpRemaining, skill.getName(), p.targetLevel));
		}
		else
		{
			msg.append(ChatColorType.HIGHLIGHT)
				.append(String.format(Locale.ROOT, "%,d more", p.actionsRemaining))
				.append(ChatColorType.NORMAL)
				.append(" to " + skill.getName() + " " + p.targetLevel
					+ " (" + xpFormat.format(xpEach) + " xp each" + (measured ? ", measured" : "") + ")");
		}

		if (levelRequired > client.getRealSkillLevel(skill))
		{
			msg.append(" - needs level " + levelRequired);
		}
		return msg.build();
	}

	String formatXp(double xp)
	{
		return xpFormat.format(xp);
	}

	private void sendChat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<Integer, Integer> readInventory()
	{
		final Map<Integer, Integer> counts = new HashMap<>();
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return counts;
		}
		for (Item item : inventory.getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				counts.merge(itemManager.canonicalize(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	private String itemName(int itemId)
	{
		final String name = itemManager.getItemComposition(itemId).getMembersName();
		return name == null || "null".equalsIgnoreCase(name) ? "Item " + itemId : name;
	}

	private static String npcName(MenuEntry entry)
	{
		final NPC npc = entry.getNpc();
		if (npc != null && npc.getName() != null)
		{
			return cleanName(npc.getName());
		}
		// "Fishing spot  (level-0)" -> "Fishing spot"
		final String target = cleanName(afterArrow(entry.getTarget()));
		return target == null ? null : cleanName(target.replaceAll("\\(level-\\d+\\)$", ""));
	}

	/** "Raw shark -> Range" -> "Range" */
	private static String afterArrow(String target)
	{
		if (target == null)
		{
			return null;
		}
		final int arrow = target.lastIndexOf("->");
		return arrow >= 0 ? target.substring(arrow + 2) : target;
	}

	private static String cleanName(String s)
	{
		if (s == null)
		{
			return null;
		}
		final String clean = Text.removeTags(s).replace(' ', ' ').trim();
		return clean.isEmpty() || "null".equalsIgnoreCase(clean) ? null : clean;
	}

	private void saveLearned()
	{
		ticksSinceSave = 0;
		if (learned.isDirty())
		{
			configManager.setConfiguration(ActionsToLevelConfig.GROUP, ActionsToLevelConfig.LEARNED_RATES_KEY, learned.serialize());
		}
	}
}
