package com.actionstolevel;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Experience;
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
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NPCManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

@PluginDescriptor(
	name = "Actions To Level",
	description = "How many more items, actions or kills you need to level, what your goals need from your bank, and what's left to gather",
	tags = {"xp", "level", "skilling", "actions", "calculator", "goal", "kills", "combat", "slayer", "ironman", "bank", "mining", "woodcutting", "fishing"}
)
@PluginDependency(SlayerPlugin.class)
public class ActionsToLevelPlugin extends Plugin
{
	static final String MENU_OPTION = "Actions to level";
	static final String GOAL_OPTION = "Add goal";

	/** An XP drop is credited to the last clicked object/NPC for this many ticks (~5 minutes). */
	private static final int TARGET_MEMORY_TICKS = 500;
	/** Ignore the XP sync the server sends while logging in / hopping. */
	private static final int LOGIN_GRACE_TICKS = 2;
	private static final int SAVE_INTERVAL_TICKS = 100;
	private static final int PANEL_REFRESH_TICKS = 5;
	/** Don't learn from ticks where lots of different items moved (banking, etc.). */
	private static final int MAX_ITEM_KINDS_TO_LEARN = 4;
	private static final int MAX_SEARCH_RESULTS = 8;

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
	private HoverOverlay hoverOverlay;

	@Inject
	private XpTable xpTable;

	@Inject
	private NPCManager npcManager;

	@Inject
	private SlayerPluginService slayerService;

	@Inject
	private Notifier notifier;

	@Inject
	private ClientToolbar clientToolbar;

	private final LearnedRates learned = new LearnedRates();
	private final RecipeBook recipes = new RecipeBook();
	private CombatTracker combat;
	private Rates rates;
	private BankTracker bank;
	private Goals goals;
	private Reports reports;
	private ActionsToLevelPanel panel;
	private NavigationButton navButton;

	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> pendingXp = new EnumMap<>(Skill.class);
	/** Most recently updated entry last. Only touched on the client thread. */
	private final LinkedHashMap<String, TrackedAction> tracked = new LinkedHashMap<>();
	private List<Reports.GoalView> overlayGoals = Collections.emptyList();

	/** Item id -> quantity at the end of the last processed tick, or null when unknown. */
	private Map<Integer, Integer> inventorySnapshot;
	private boolean pendingWaitedATick;
	private int loginGraceTicks;
	private String lastTarget;
	private int lastTargetTick;
	private int ticksSinceSave;
	private int ticksSincePanel;

	@Provides
	ActionsToLevelConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ActionsToLevelConfig.class);
	}

	@Override
	protected void startUp()
	{
		learned.deserialize(configManager.getConfiguration(ActionsToLevelConfig.GROUP, ActionsToLevelConfig.LEARNED_RATES_KEY));
		recipes.deserialize(configManager.getConfiguration(ActionsToLevelConfig.GROUP, RecipeBook.RECIPES_KEY));
		combat = new CombatTracker(client, npcManager, slayerService, learned);
		combat.deserializeMonsters(configManager.getConfiguration(ActionsToLevelConfig.GROUP, ActionsToLevelConfig.MONSTERS_KEY));
		rates = new Rates(xpTable, learned, recipes, itemManager, config);
		bank = new BankTracker(client, itemManager, configManager);
		goals = new Goals(configManager);
		reports = new Reports(client, config, rates, combat, bank, this::findTracked);

		overlayManager.add(overlay);
		overlayManager.add(hoverOverlay);
		resetSession();
		buildPanel();

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::seedFromClient);
		}
	}

	@Override
	protected void shutDown()
	{
		saveAll();
		bank.reset();
		goals.reset();
		overlayManager.remove(overlay);
		overlayManager.remove(hoverOverlay);
		removePanel();
		tracked.clear();
		overlayGoals = Collections.emptyList();
		resetSession();
	}

	Reports reports()
	{
		return reports;
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
		bank.ensureLoaded();
		goals.ensureLoaded();
		refreshGoals();
		refreshPanel();
	}

	// ------------------------------------------------------------------
	// Events
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
				saveAll();
				resetSession();
				tracked.clear();
				bank.reset();
				goals.reset();
				overlayGoals = Collections.emptyList();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// A different account: its own bank and goals
		clientThread.invokeLater(() ->
		{
			bank.reset();
			goals.reset();
			bank.ensureLoaded();
			goals.ensureLoaded();
			refreshGoals();
			refreshPanel();
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ActionsToLevelConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("showPanel".equals(event.getKey()))
		{
			removePanel();
			buildPanel();
		}
		clientThread.invokeLater(() ->
		{
			refreshGoals();
			refreshPanel();
		});
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

		final int oldLevel = Experience.getLevelForXp(previous);
		final int newLevel = Experience.getLevelForXp(event.getXp());
		if (newLevel > oldLevel)
		{
			onLevelUp(skill, newLevel);
		}

		// Combat XP (and Magic while fighting) is counted per kill instead
		if (combat.onXp(skill, gained, client.getTickCount()))
		{
			return;
		}

		pendingXp.merge(skill, gained, Integer::sum);
	}

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

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		bank.onContainerChanged(event.getContainerId(), event.getItemContainer());
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
		checkAlmostThere();
		bank.onTick();

		if (++ticksSincePanel >= PANEL_REFRESH_TICKS)
		{
			ticksSincePanel = 0;
			refreshGoals();
			if (panel != null && panel.isShowing())
			{
				refreshPanel();
			}
		}

		if (++ticksSinceSave >= SAVE_INTERVAL_TICKS)
		{
			saveAll();
		}
	}

	// ------------------------------------------------------------------
	// Skilling: XP drop -> which item / object caused it
	// ------------------------------------------------------------------

	private void onXpDrop(Skill skill, int xp, InventoryDiff diff)
	{
		final InventoryDiff.Change primary = pickPrimaryItem(skill, diff);
		final String target = recentTarget();
		final String alchemy = skill == Skill.MAGIC ? alchemyName(diff, xp) : null;

		// Alching isn't "XP per coin", so don't learn from it
		if (config.learnRates() && alchemy == null)
		{
			learnFrom(skill, xp, diff, primary, target);
			recipes.learn(skill, diff, primary);
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
			name = rates.itemName(primary.itemId);
			xpEach = rates.bestRate(LearnedRates.itemKey(primary.itemId), skill, base, (double) xp / primary.quantity);
			levelRequired = base != null ? base.level : 0;
		}
		else if (target != null)
		{
			final XpRate base = rates.forTarget(target, false).get(skill);
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
		action.recordActions(System.currentTimeMillis(), Math.max(1, (int) Math.round(xp / Math.max(0.01, xpEach))), xp);
		if (config.chatOnXpDrop())
		{
			sendChat(reports.countLine(action.name, new XpRate(skill, xpEach, levelRequired, false, null)).chat());
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

	// ------------------------------------------------------------------
	// Combat: kills -> XP per kill
	// ------------------------------------------------------------------

	private void onKill(CombatTracker.Kill kill)
	{
		final Integer hp = combat.hitpoints(null, kill.name);
		final Map<Skill, XpRate> killRates = combat.rates(kill.name, hp, kill.style, kill.observed);
		final List<XpRate> chatLines = new ArrayList<>();

		// Tracked oldest-first, so the style's own skills end up on top of the overlay
		if (config.showSlayer() && combat.gaveSlayerXp(kill.name))
		{
			final XpRate slayer = combat.slayerRate(kill.name, hp, combat.slayerNote(kill.name));
			if (slayer != null)
			{
				trackKill(kill, slayer);
				chatLines.add(slayer);
			}
		}

		final XpRate hitpoints = killRates.get(Skill.HITPOINTS);
		if (hitpoints != null && config.showHitpoints())
		{
			trackKill(kill, hitpoints);
		}

		for (XpRate rate : killRates.values())
		{
			if (rate.skill != Skill.HITPOINTS)
			{
				trackKill(kill, rate);
				chatLines.add(0, rate);
			}
		}

		if (config.chatOnXpDrop())
		{
			for (XpRate rate : chatLines)
			{
				sendChat(reports.killLine(kill.name, rate, true).chat());
			}
		}
	}

	private void trackKill(CombatTracker.Kill kill, XpRate rate)
	{
		final TrackedAction action = track(rate.skill, kill.name, rate.xp, 0);
		action.perKill = true;
		action.note = rate.note;
		action.recordActions(System.currentTimeMillis(), kill.count, rate.xp * kill.count);
	}

	// ------------------------------------------------------------------
	// Overlay lines, pace and alerts
	// ------------------------------------------------------------------

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

	/** The overlay line for a skill and name, if there is one (used for pace). */
	TrackedAction findTracked(Skill skill, String name)
	{
		return name == null ? null : tracked.get(TrackedAction.key(skill, name));
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

	/** "10 Iron ore left to Mining 42" once per level per thing you're doing. */
	private void checkAlmostThere()
	{
		final int threshold = Math.max(1, config.alertAt());
		for (TrackedAction a : tracked.values())
		{
			final LevelMath.Progress p = progressFor(a.skill, a.xpEach);
			if (p.isMaxed() || p.actionsRemaining < 0 || p.actionsRemaining > threshold || a.alertedForLevel == p.targetLevel)
			{
				continue;
			}
			a.alertedForLevel = p.targetLevel;
			final String what = a.perKill ? (p.actionsRemaining == 1 ? "kill" : "kills") + " of " + a.name : a.name;
			notifier.notify(config.nearLevelNotification(),
				p.actionsRemaining + " " + what + " left to " + a.skill.getName() + " " + p.targetLevel);
		}
	}

	private void onLevelUp(Skill skill, int level)
	{
		if (config.unlockMessages() && level <= Experience.MAX_REAL_LEVEL)
		{
			xpTable.buildNameIndex(itemManager);
			for (Line line : reports.levelUp(skill, level, xpTable))
			{
				sendChat(line.chat());
			}
		}

		for (Goals.Goal g : goals.all())
		{
			if (!g.reached && g.skill == skill && level >= g.targetLevel)
			{
				goals.markReached(g);
				final String msg = "Goal reached: " + skill.getName() + " " + g.targetLevel + " (" + g.name + ")";
				notifier.notify(config.goalNotification(), msg);
				sendChat(Line.main().strong(msg).chat());
			}
		}
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
		return reports.progressFor(skill, xpEach);
	}

	String formatXp(double xp)
	{
		return reports.formatXp(xp);
	}

	/** Goals for the overlay, worked out on the game tick rather than every frame. */
	List<Reports.GoalView> overlayGoals()
	{
		return overlayGoals;
	}

	private void refreshGoals()
	{
		if (client.getGameState() != GameState.LOGGED_IN || !config.showGoalsOnOverlay())
		{
			overlayGoals = Collections.emptyList();
			return;
		}
		final List<Reports.GoalView> views = new ArrayList<>();
		for (Goals.Goal g : goals.all())
		{
			if (views.size() >= config.goalsOnOverlay())
			{
				break;
			}
			views.add(reports.goal(g));
		}
		overlayGoals = views;
	}

	// ------------------------------------------------------------------
	// Right-click "Actions to level" / "Add goal"
	// ------------------------------------------------------------------

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		final boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		if (config.requireShift() && !shift)
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
			final Runnable onGoal;
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
					final Map<Skill, XpRate> r = rates.forTarget(name, true);
					known = !r.isEmpty();
					onClick = () -> sendLines(reports.object(name, false));
					onGoal = () -> addGoalsFromMenu(r, Goals.Goal.Kind.ACTION, -1, name);
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
					known = attackable || !rates.forTarget(name, true).isEmpty();
					onClick = () -> sendLines(reports.npc(npc, name, attackable, false));
					onGoal = () -> addNpcGoalsFromMenu(npc, name, attackable);
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
					final Map<Skill, XpRate> r = rates.forItem(itemId);
					known = !r.isEmpty();
					onClick = () -> sendLines(reports.item(itemId, false));
					onGoal = () -> addGoalsFromMenu(r, Goals.Goal.Kind.ITEM, itemId, rates.itemName(itemId));
					break;
				}
				default:
					continue;
			}

			if (!known && config.onlyWhenKnown())
			{
				continue;
			}

			// Inserting at the Examine entry's index puts our options directly below Examine
			if (shift && config.goalMenuOption() && known)
			{
				client.getMenu().createMenuEntry(idx)
					.setOption(GOAL_OPTION)
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e -> onGoal.run());
			}
			client.getMenu().createMenuEntry(idx)
				.setOption(MENU_OPTION)
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> onClick.run());
		}
	}

	private void addGoalsFromMenu(Map<Skill, XpRate> skillRates, Goals.Goal.Kind kind, int itemId, String name)
	{
		for (Skill skill : skillRates.keySet())
		{
			addGoal(skill, kind, itemId, name, 0);
		}
	}

	private void addNpcGoalsFromMenu(NPC npc, String name, boolean attackable)
	{
		final Map<Skill, XpRate> skilling = rates.forTarget(name, true);
		for (Skill skill : skilling.keySet())
		{
			addGoal(skill, Goals.Goal.Kind.ACTION, -1, name, 0);
		}
		if (!attackable)
		{
			return;
		}
		final Integer hp = combat.hitpoints(npc, name);
		for (Skill skill : combat.rates(name, hp, combat.currentStyle(), null).keySet())
		{
			if (skill != Skill.HITPOINTS)
			{
				addGoal(skill, Goals.Goal.Kind.MONSTER, -1, name, 0);
			}
		}
		if (config.showSlayer() && combat.remainingOnTask() > 0)
		{
			addGoal(Skill.SLAYER, Goals.Goal.Kind.MONSTER, -1, name, 0);
		}
	}

	/** @param target goal level, or 0 for the "Count to level" setting or else the next level */
	private void addGoal(Skill skill, Goals.Goal.Kind kind, int itemId, String name, int target)
	{
		final int current = Experience.getLevelForXp(client.getSkillExperience(skill));
		int level = target > current ? target : LevelMath.targetLevel(current, config.targetLevel(), true);
		if (level < 0)
		{
			level = Experience.MAX_VIRT_LEVEL;
		}
		goals.add(skill, kind, itemId, name, level);
		sendChat(Line.main().text("Goal added: ").strong(skill.getName() + " " + level).text(" (" + name + ")").chat());
		refreshGoals();
		refreshPanel();
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

	// ------------------------------------------------------------------
	// Side panel
	// ------------------------------------------------------------------

	private void buildPanel()
	{
		if (!config.showPanel() || navButton != null)
		{
			return;
		}
		panel = new ActionsToLevelPanel();
		panel.setCallbacks(
			query -> clientThread.invokeLater(this::refreshPanel),
			(spec, target) -> clientThread.invokeLater(() -> addGoal(spec.skill, spec.kind, spec.itemId, spec.name, target)),
			id -> clientThread.invokeLater(() ->
			{
				goals.remove(id);
				refreshGoals();
				refreshPanel();
			}),
			(id, target) -> clientThread.invokeLater(() ->
			{
				goals.setTarget(id, target);
				refreshGoals();
				refreshPanel();
			}));

		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(ActionsToLevelPlugin.class, "panel_icon.png");
		}
		catch (RuntimeException e)
		{
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		}
		navButton = NavigationButton.builder()
			.tooltip("Actions To Level")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	private void removePanel()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
	}

	/** Rebuild the panel's contents. Client thread only. */
	private void refreshPanel()
	{
		if (panel == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		xpTable.buildNameIndex(itemManager);

		final ActionsToLevelPanel.Data data = new ActionsToLevelPanel.Data();
		data.query = panel.query();
		data.results = search(data.query);

		final List<ActionsToLevelPanel.Card> goalCards = new ArrayList<>();
		final List<Reports.GoalView> views = new ArrayList<>();
		for (Goals.Goal g : goals.all())
		{
			final Reports.GoalView v = reports.goal(g);
			views.add(v);
			goalCards.add(new ActionsToLevelPanel.Card(v.title, v.lines, Collections.emptyList(), g.id, g.targetLevel, v.reached));
		}
		data.goals = goalCards;

		final List<Line> gather = new ArrayList<>();
		for (Reports.Material m : reports.gatherList(views))
		{
			gather.add(Line.main().strong(m.name).text(": " + Reports.fmt(m.missing) + (m.source != null ? " (" + m.source + ")" : "")));
		}
		data.gather = gather;
		data.slayer = slayerLines();
		data.session = sessionLines();
		panel.update(data);
	}

	private List<ActionsToLevelPanel.Card> search(String query)
	{
		final String q = query.toLowerCase(Locale.ROOT);
		if (q.length() < 2)
		{
			return Collections.emptyList();
		}

		final List<ActionsToLevelPanel.Card> cards = new ArrayList<>();
		final Set<Integer> items = new LinkedHashSet<>();
		final Set<String> actions = new LinkedHashSet<>();

		// Your bank first: that's what an ironman has to work with
		for (int id : bank.bankItemIds())
		{
			if (rates.itemName(id).toLowerCase(Locale.ROOT).contains(q))
			{
				items.add(id);
			}
		}
		for (XpTable.Entry e : xpTable.named())
		{
			if (e.name.contains(q))
			{
				if (e.isNamedAction())
				{
					actions.add(e.displayName);
				}
				else
				{
					items.add(e.itemId);
				}
			}
		}
		for (ItemPrice price : rates.searchItems(q))
		{
			if (items.size() >= MAX_SEARCH_RESULTS * 2)
			{
				break;
			}
			items.add(price.getId());
		}

		for (Map.Entry<String, Integer> m : combat.seenMonsters().entrySet())
		{
			if (cards.size() >= MAX_SEARCH_RESULTS)
			{
				break;
			}
			if (!m.getKey().toLowerCase(Locale.ROOT).contains(q))
			{
				continue;
			}
			final List<ActionsToLevelPanel.GoalSpec> specs = new ArrayList<>();
			final Map<Skill, XpRate> combatRates = combat.rates(m.getKey(), m.getValue(), combat.currentStyle(), null);
			for (Skill skill : combatRates.keySet())
			{
				specs.add(new ActionsToLevelPanel.GoalSpec(skill, Goals.Goal.Kind.MONSTER, -1, m.getKey()));
			}
			specs.add(new ActionsToLevelPanel.GoalSpec(Skill.SLAYER, Goals.Goal.Kind.MONSTER, -1, m.getKey()));
			cards.add(ActionsToLevelPanel.Card.info(m.getKey(), reports.npc(null, m.getKey(), true, false), specs));
		}

		for (int id : items)
		{
			if (cards.size() >= MAX_SEARCH_RESULTS)
			{
				break;
			}
			final Map<Skill, XpRate> r = rates.forItem(id);
			if (r.isEmpty())
			{
				continue;
			}
			final String name = rates.itemName(id);
			final List<ActionsToLevelPanel.GoalSpec> specs = new ArrayList<>();
			for (Skill skill : r.keySet())
			{
				specs.add(new ActionsToLevelPanel.GoalSpec(skill, Goals.Goal.Kind.ITEM, id, name));
			}
			cards.add(ActionsToLevelPanel.Card.info(name, reports.item(id, false), specs));
		}

		for (String name : actions)
		{
			if (cards.size() >= MAX_SEARCH_RESULTS)
			{
				break;
			}
			final Map<Skill, XpRate> r = rates.forTarget(name, true);
			final List<Line> lines = new ArrayList<>();
			final List<ActionsToLevelPanel.GoalSpec> specs = new ArrayList<>();
			for (XpRate rate : r.values())
			{
				lines.add(reports.countLine(name, rate));
				specs.add(new ActionsToLevelPanel.GoalSpec(rate.skill, Goals.Goal.Kind.ACTION, -1, name));
			}
			if (lines.isEmpty())
			{
				// Agility courses, farming patches, construction builds: not tied to a clickable name
				for (XpTable.Entry e : xpTable.forName(name))
				{
					final XpRate rate = new XpRate(e.skill, e.xp, e.level, false, null);
					lines.add(reports.countLine(name, rate));
					specs.add(new ActionsToLevelPanel.GoalSpec(e.skill, Goals.Goal.Kind.ACTION, -1, name));
				}
			}
			if (!lines.isEmpty())
			{
				cards.add(ActionsToLevelPanel.Card.info(name, lines, specs));
			}
		}
		return cards;
	}

	private List<Line> slayerLines()
	{
		final int remaining = combat.remainingOnTask();
		if (remaining <= 0)
		{
			return Collections.emptyList();
		}
		final List<Line> lines = new ArrayList<>();
		String task = null;
		try
		{
			task = slayerService.getTask();
		}
		catch (RuntimeException ignored)
		{
			// Slayer plugin off
		}
		lines.add(Line.main().strong(task != null ? task : "Current task").text(": " + Reports.fmt(remaining) + " left"));

		for (TrackedAction a : tracked.values())
		{
			if (a.skill == Skill.SLAYER)
			{
				final LevelMath.Projection pr = LevelMath.project(client.getSkillExperience(Skill.SLAYER), remaining * a.xpEach, config.virtualLevels());
				lines.add(Line.detail().text("Finishing it (" + a.name + ", " + reports.formatXp(a.xpEach) + " xp each): Slayer "
					+ (pr.levelsUp() ? "to " + pr.toLevel : "stays " + pr.toLevel) + ", " + Reports.fmt(pr.shortOfNext) + " xp short of " + (pr.toLevel + 1)));
				break;
			}
		}
		return lines;
	}

	private List<Line> sessionLines()
	{
		final long now = System.currentTimeMillis();
		final List<Line> lines = new ArrayList<>();
		for (TrackedAction a : getTrackedNewestFirst())
		{
			final LevelMath.Progress p = progressFor(a.skill, a.xpEach);
			final Line l = Line.main().strong(a.name).text(" (" + a.skill.getName() + "): " + Reports.fmt(a.sessionCount)
				+ (a.perKill ? " kills" : " done"));
			final double perHour = a.perHour(now);
			if (perHour > 0)
			{
				l.text(", " + Reports.fmt(Math.round(perHour)) + "/h");
			}
			if (p.actionsRemaining >= 0 && !p.isMaxed())
			{
				l.text(", " + Reports.fmt(p.actionsRemaining) + " to " + p.targetLevel);
				final long eta = a.etaMillis(now, p.actionsRemaining);
				if (eta >= 0)
				{
					l.text(" ~" + TrackedAction.formatDuration(eta));
				}
			}
			lines.add(l);
		}
		return lines;
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private void sendLines(List<Line> lines)
	{
		for (Line line : lines)
		{
			sendChat(line.chat());
		}
	}

	private void sendChat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

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
	static String afterArrow(String target)
	{
		if (target == null)
		{
			return null;
		}
		final int arrow = target.lastIndexOf("->");
		return arrow >= 0 ? target.substring(arrow + 2) : target;
	}

	static String cleanName(String s)
	{
		if (s == null)
		{
			return null;
		}
		final String clean = Text.removeTags(s).replace(' ', ' ').trim();
		return clean.isEmpty() || "null".equalsIgnoreCase(clean) ? null : clean;
	}

	private void saveAll()
	{
		ticksSinceSave = 0;
		if (learned.isDirty())
		{
			configManager.setConfiguration(ActionsToLevelConfig.GROUP, ActionsToLevelConfig.LEARNED_RATES_KEY, learned.serialize());
		}
		if (recipes.isDirty())
		{
			configManager.setConfiguration(ActionsToLevelConfig.GROUP, RecipeBook.RECIPES_KEY, recipes.serialize());
		}
		if (combat != null && combat.isMonstersDirty())
		{
			configManager.setConfiguration(ActionsToLevelConfig.GROUP, ActionsToLevelConfig.MONSTERS_KEY, combat.serializeMonsters());
		}
		if (bank != null)
		{
			bank.save();
		}
	}
}
