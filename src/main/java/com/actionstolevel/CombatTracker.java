package com.actionstolevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.NPCManager;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.util.Text;

/**
 * Works out XP per kill for monsters, from the monster's hitpoints and your attack style,
 * and learns the real values (XP bonuses, spell XP) from the kills you make.
 */
final class CombatTracker
{
	/** Skills that get XP from dealing damage. */
	static final Set<Skill> DAMAGE_SKILLS = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS);

	/** Forget half-finished fights after this long. */
	private static final int RESET_AFTER_TICKS = 100;
	/** Slayer XP within this many ticks of a kill belongs to that kill. */
	private static final int SLAYER_WINDOW_TICKS = 10;
	/** Magic XP this soon after your last hit, with a combat spell style, still counts as combat. */
	private static final int MAGIC_WINDOW_TICKS = 3;

	/** One finished kill (or several of the same monster on one tick). */
	static final class Kill
	{
		final String name;
		final int count;
		final CombatStyle style;
		/** XP per kill actually gained for this batch, per skill. */
		final Map<Skill, Double> observed;

		Kill(String name, int count, CombatStyle style, Map<Skill, Double> observed)
		{
			this.name = name;
			this.count = count;
			this.style = style;
			this.observed = observed;
		}
	}

	private final Client client;
	private final NPCManager npcManager;
	private final SlayerPluginService slayer;
	private final LearnedRates learned;

	/** NPC index -> name, for NPCs you've hit. */
	private final Map<Integer, String> hitNpcs = new HashMap<>();
	/** Monster names you've had Slayer XP from. */
	private final Set<String> slayerNames = new HashSet<>();
	/** Hitpoints by monster name, remembered from NPCs you've seen. */
	private final Map<String, Integer> hitpointsByName = new HashMap<>();
	private final Map<Skill, Integer> xpSinceKill = new EnumMap<>(Skill.class);
	private final List<String> pendingKills = new ArrayList<>();
	private int pendingKillTick = -1;
	private int lastHitTick = -1000;
	private String lastKillName;
	private int lastKillTick = -1000;
	/** Kills that could give Slayer XP (on task, or unknown) since the last Slayer XP drop. */
	private int killsSinceSlayerXp;
	private int lastSlayerKillTick = -1000;

	CombatTracker(Client client, NPCManager npcManager, SlayerPluginService slayer, LearnedRates learned)
	{
		this.client = client;
		this.npcManager = npcManager;
		this.slayer = slayer;
		this.learned = learned;
	}

	void reset()
	{
		hitNpcs.clear();
		xpSinceKill.clear();
		pendingKills.clear();
		pendingKillTick = -1;
		lastHitTick = -1000;
		lastKillName = null;
		lastKillTick = -1000;
		killsSinceSlayerXp = 0;
		lastSlayerKillTick = -1000;
	}

	static String combatKey(String name, CombatStyle style)
	{
		return "c:" + name.toLowerCase(Locale.ROOT) + "|" + style.name();
	}

	static String slayerKey(String name)
	{
		return "s:" + name.toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------
	// Events
	// ------------------------------------------------------------------

	void onMyHitsplat(NPC npc, int tick)
	{
		final String name = npcName(npc);
		if (name == null)
		{
			return;
		}
		hitNpcs.put(npc.getIndex(), name);
		hitpoints(npc, name);
		lastHitTick = tick;
	}

	/** @return true if the death was a monster you'd been fighting */
	boolean onNpcDeath(NPC npc, int tick)
	{
		final String name = hitNpcs.remove(npc.getIndex());
		if (name == null)
		{
			return false;
		}
		// Kills on consecutive ticks are batched and the XP is split between them
		pendingKills.add(name);
		pendingKillTick = tick;
		lastKillName = name;
		lastKillTick = tick;

		// Off-task kills never give Slayer XP, so they mustn't share it
		if (isOnTask(npc, name) != Boolean.FALSE)
		{
			if (tick - lastSlayerKillTick > SLAYER_WINDOW_TICKS)
			{
				killsSinceSlayerXp = 0;
			}
			killsSinceSlayerXp++;
			lastSlayerKillTick = tick;
		}
		return true;
	}

	void onNpcDespawned(NPC npc)
	{
		hitNpcs.remove(npc.getIndex());
	}

	/**
	 * @return true if this XP was combat XP and has been taken care of here
	 */
	boolean onXp(Skill skill, int gained, int tick)
	{
		if (skill == Skill.SLAYER)
		{
			if (lastKillName != null && tick - lastKillTick <= SLAYER_WINDOW_TICKS)
			{
				final int kills = Math.max(1, killsSinceSlayerXp);
				for (int i = 0; i < kills; i++)
				{
					learned.record(slayerKey(lastKillName), Skill.SLAYER, (double) gained / kills);
				}
				slayerNames.add(lastKillName.toLowerCase(Locale.ROOT));
			}
			killsSinceSlayerXp = 0;
			return true;
		}

		if (!DAMAGE_SKILLS.contains(skill))
		{
			return false;
		}

		if (skill == Skill.MAGIC && !isCastingInCombat(tick))
		{
			// Alching, teleports, enchanting... leave it to the skilling tracker
			return false;
		}

		xpSinceKill.merge(skill, gained, Integer::sum);
		return true;
	}

	/**
	 * Call once per game tick. Returns kills that have finished (one tick after the death,
	 * so XP from the final hit has arrived).
	 */
	Kill onTick(int tick)
	{
		if (pendingKills.isEmpty())
		{
			if (tick - lastHitTick > RESET_AFTER_TICKS)
			{
				xpSinceKill.clear();
			}
			return null;
		}
		if (tick <= pendingKillTick)
		{
			return null;
		}

		final String name = pendingKills.get(0);
		boolean sameName = true;
		for (String n : pendingKills)
		{
			sameName &= n.equals(name);
		}
		final int count = pendingKills.size();
		final CombatStyle style = CombatStyle.current(client);

		final Map<Skill, Double> observed = new EnumMap<>(Skill.class);
		for (Map.Entry<Skill, Integer> e : xpSinceKill.entrySet())
		{
			if (e.getValue() > 0)
			{
				observed.put(e.getKey(), (double) e.getValue() / count);
			}
		}

		if (sameName && style.isKnown())
		{
			for (Map.Entry<Skill, Double> e : observed.entrySet())
			{
				for (int i = 0; i < count; i++)
				{
					learned.recordAverage(combatKey(name, style), e.getKey(), e.getValue());
				}
			}
		}

		pendingKills.clear();
		xpSinceKill.clear();
		return sameName ? new Kill(name, count, style, observed) : null;
	}

	// ------------------------------------------------------------------
	// Rates
	// ------------------------------------------------------------------

	CombatStyle currentStyle()
	{
		return CombatStyle.current(client);
	}

	/**
	 * XP per kill for each skill your current style trains, plus Hitpoints.
	 *
	 * @param fallback observed XP from the kill that was just made, used when nothing else is known
	 */
	Map<Skill, XpRate> rates(String name, Integer hitpoints, CombatStyle style, Map<Skill, Double> fallback)
	{
		final Map<Skill, XpRate> rates = new EnumMap<>(Skill.class);
		if (!style.isKnown())
		{
			return rates;
		}

		final Map<Skill, LearnedRates.Rate> measured = learned.get(combatKey(name, style));
		final Map<Skill, Double> perDamage = new EnumMap<>(style.xpPerDamage);
		perDamage.put(Skill.HITPOINTS, CombatStyle.HITPOINTS_XP_PER_DAMAGE);

		for (Map.Entry<Skill, Double> e : perDamage.entrySet())
		{
			final Skill skill = e.getKey();
			final LearnedRates.Rate rate = measured.get(skill);
			if (rate != null && rate.samples >= 2)
			{
				rates.put(skill, XpRate.perKill(skill, rate.average(), true, null));
			}
			else if (hitpoints != null && hitpoints > 0)
			{
				rates.put(skill, XpRate.perKill(skill, hitpoints * e.getValue(), false, null));
			}
			else if (fallback != null && fallback.get(skill) != null)
			{
				rates.put(skill, XpRate.perKill(skill, fallback.get(skill), true, null));
			}
		}
		return rates;
	}

	/** Slayer XP per kill: what you've actually received, or the monster's hitpoints. */
	XpRate slayerRate(String name, Integer hitpoints, String note)
	{
		final LearnedRates.Rate rate = learned.get(slayerKey(name), Skill.SLAYER);
		if (rate != null && rate.samples >= 1)
		{
			return XpRate.perKill(Skill.SLAYER, rate.average(), true, note);
		}
		if (hitpoints != null && hitpoints > 0)
		{
			return XpRate.perKill(Skill.SLAYER, hitpoints, false, note);
		}
		return null;
	}

	/** Task status line for a live NPC: "on task, 85 left" / "not your task" / "no task". */
	String slayerNote(NPC npc, String name)
	{
		final int remaining = remainingOnTask();
		if (remaining <= 0)
		{
			return "only on a Slayer task";
		}
		final Boolean onTask = isOnTask(npc, name);
		if (onTask == null)
		{
			return remaining + " left on task, if it's your task";
		}
		return onTask ? "on task, " + remaining + " left" : "not your current task";
	}

	/** Task status for an overlay line, where the NPC may be gone. */
	String slayerNote(String name)
	{
		final int remaining = remainingOnTask();
		if (remaining <= 0 || !slayerNames.contains(name.toLowerCase(Locale.ROOT)))
		{
			return null;
		}
		return remaining + " left";
	}

	boolean gaveSlayerXp(String name)
	{
		return slayerNames.contains(name.toLowerCase(Locale.ROOT));
	}

	int remainingOnTask()
	{
		try
		{
			if (slayer != null && slayer.getTask() != null)
			{
				return slayer.getRemainingAmount();
			}
		}
		catch (RuntimeException ignored)
		{
			// Slayer plugin not available; fall back to the game's own counter
		}
		return client.getVarpValue(VarPlayerID.SLAYER_COUNT);
	}

	/** @return true/false when known, null when the Slayer plugin can't tell */
	private Boolean isOnTask(NPC npc, String name)
	{
		if (npc != null && slayer != null)
		{
			try
			{
				if (slayer.getTask() != null)
				{
					return slayer.getTargets().contains(npc);
				}
			}
			catch (RuntimeException ignored)
			{
				// fall through
			}
		}
		return gaveSlayerXp(name) ? Boolean.TRUE : null;
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	Integer hitpoints(NPC npc, String name)
	{
		Integer hp = npc == null ? null : npcManager.getHealth(npc.getId());
		if (hp == null && npc != null)
		{
			final NPCComposition comp = npc.getTransformedComposition();
			if (comp != null)
			{
				hp = npcManager.getHealth(comp.getId());
			}
		}
		if (hp != null && hp > 0)
		{
			hitpointsByName.put(name.toLowerCase(Locale.ROOT), hp);
			return hp;
		}
		return name == null ? null : hitpointsByName.get(name.toLowerCase(Locale.ROOT));
	}

	static boolean isAttackable(NPC npc)
	{
		if (npc == null)
		{
			return false;
		}
		final NPCComposition comp = npc.getTransformedComposition();
		if (comp == null || comp.getActions() == null)
		{
			return false;
		}
		for (String action : comp.getActions())
		{
			if ("Attack".equals(action))
			{
				return true;
			}
		}
		return false;
	}

	/** Magic XP counts as combat while you're attacking a monster, not when alching between kills. */
	private boolean isCastingInCombat(int tick)
	{
		final Player me = client.getLocalPlayer();
		final Actor target = me == null ? null : me.getInteracting();
		if (target instanceof NPC && isAttackable((NPC) target))
		{
			return true;
		}
		if (tick - lastHitTick > MAGIC_WINDOW_TICKS)
		{
			return false;
		}
		final CombatStyle style = CombatStyle.current(client);
		return style == CombatStyle.CASTING || style == CombatStyle.DEFENSIVE_CASTING;
	}

	static String npcName(NPC npc)
	{
		if (npc == null)
		{
			return null;
		}
		final NPCComposition comp = npc.getTransformedComposition();
		String name = comp != null ? comp.getName() : npc.getName();
		if (name == null)
		{
			return null;
		}
		name = Text.removeTags(name).replace(' ', ' ').trim();
		return name.isEmpty() || "null".equalsIgnoreCase(name) ? null : name;
	}
}
