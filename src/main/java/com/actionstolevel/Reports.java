package com.actionstolevel;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.NPC;
import net.runelite.api.Skill;

/**
 * Builds every report the plugin shows (right-click, hover tooltip, side panel, goals) as
 * {@link Line}s, so they all read the same. Must be used on the client thread.
 */
final class Reports
{
	/** Prayer XP multipliers for bones at altars, and ashes with Demonic offering. */
	private static final double GILDED_ALTAR = 3.5;
	private static final double ECTOFUNTUS = 4.0;
	private static final double CHAOS_ALTAR = 3.5;
	private static final double DEMONIC_OFFERING = 3.0;

	private final Client client;
	private final ActionsToLevelConfig config;
	private final Rates rates;
	private final CombatTracker combat;
	private final BankTracker bank;
	/** (skill, name) -> the overlay line for it, for pace. */
	private final BiFunction<Skill, String, TrackedAction> paceLookup;
	private final DecimalFormat xpFormat = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

	Reports(Client client, ActionsToLevelConfig config, Rates rates, CombatTracker combat, BankTracker bank,
		BiFunction<Skill, String, TrackedAction> paceLookup)
	{
		this.client = client;
		this.config = config;
		this.rates = rates;
		this.combat = combat;
		this.bank = bank;
		this.paceLookup = paceLookup;
	}

	// ------------------------------------------------------------------
	// Items, objects, NPCs
	// ------------------------------------------------------------------

	List<Line> item(int itemId, boolean brief)
	{
		final String name = rates.itemName(itemId);
		final Map<Skill, XpRate> itemRates = rates.forItem(itemId);
		final List<Line> lines = new ArrayList<>();
		if (itemRates.isEmpty())
		{
			if (!brief)
			{
				lines.add(Line.main().strong(name).text(": no XP info yet. Do it once and the plugin will learn it."));
			}
			return lines;
		}

		boolean countsShown = false;
		for (XpRate rate : itemRates.values())
		{
			lines.add(countLine(name, rate));

			if (!config.showBankCounts())
			{
				continue;
			}
			final int before = lines.size();
			if (rate.consumesItemId > 0)
			{
				haveLines(lines, rate, brief);
			}
			else
			{
				final RecipeBook.Recipe recipe = rates.recipeFor(itemId, rate.skill);
				if (recipe != null)
				{
					recipeLines(lines, rate, recipe, brief);
				}
			}
			countsShown |= lines.size() > before;

			if (rate.skill == Skill.PRAYER && rate.consumesItemId > 0 && config.prayerAltars() && !brief)
			{
				prayerLines(lines, name, rate);
			}
		}
		if (countsShown && !brief && !bank.bankKnown())
		{
			lines.add(Line.detail().text("(Counts are what you're carrying. Open your bank once to include it.)"));
		}
		return lines;
	}

	List<Line> object(String name, boolean brief)
	{
		final Map<Skill, XpRate> targetRates = rates.forTarget(name, true);
		final List<Line> lines = new ArrayList<>();
		if (targetRates.isEmpty())
		{
			if (!brief)
			{
				lines.add(Line.main().strong(name).text(": no XP info yet. Do it once and the plugin will learn it."));
			}
			return lines;
		}
		for (XpRate rate : targetRates.values())
		{
			lines.add(countLine(name, rate));
		}
		return lines;
	}

	List<Line> npc(NPC npc, String name, boolean attackable, boolean brief)
	{
		final List<Line> lines = new ArrayList<>();
		final Map<Skill, XpRate> skilling = rates.forTarget(name, true);
		for (XpRate rate : skilling.values())
		{
			lines.add(countLine(name, rate));
		}

		if (!attackable)
		{
			if (skilling.isEmpty() && !brief)
			{
				lines.addAll(object(name, false));
			}
			return lines;
		}

		final CombatStyle style = combat.currentStyle();
		final Integer hp = combat.hitpoints(npc, name);
		final Map<Skill, XpRate> combatRates = combat.rates(name, hp, style, null);

		final Line header = Line.main().strong(name).text(hp != null ? " (" + hp + " hitpoints)" : "");
		if (!style.isKnown())
		{
			header.text(": couldn't read your attack style. Open the combat tab once and try again.");
		}
		else if (combatRates.isEmpty())
		{
			header.text(", " + style.displayName + ": hitpoints unknown. Kill one and the plugin will learn its XP.");
		}
		else
		{
			header.text(", " + style.displayName + ":");
		}
		lines.add(header);

		for (XpRate rate : combatRates.values())
		{
			if (rate.skill != Skill.HITPOINTS)
			{
				lines.add(killLine(name, rate));
			}
		}
		final XpRate hitpoints = combatRates.get(Skill.HITPOINTS);
		if (hitpoints != null && !brief)
		{
			lines.add(killLine(name, hitpoints));
		}

		XpRate slayer = null;
		if (config.showSlayer())
		{
			slayer = combat.slayerRate(name, hp, combat.slayerNote(npc, name));
			if (slayer != null)
			{
				lines.add(killLine(name, slayer));
			}
		}

		if (brief)
		{
			return lines;
		}

		// What finishing your Slayer task gets you
		final int remaining = combat.remainingOnTask();
		if (slayer != null && remaining > 0 && slayer.note != null && slayer.note.startsWith("on task"))
		{
			final Line projection = Line.detail().text("Finishing your task (" + fmt(remaining) + " kills): ");
			boolean first = true;
			final List<XpRate> projected = new ArrayList<>();
			projected.add(slayer);
			for (XpRate rate : combatRates.values())
			{
				if (rate.skill != Skill.HITPOINTS)
				{
					projected.add(rate);
				}
			}
			for (XpRate rate : projected)
			{
				if (!first)
				{
					projection.text("; ");
				}
				first = false;
				projectInto(projection, rate.skill, remaining * rate.xp);
			}
			lines.add(projection);
		}

		// Kills with the weapon's other styles, to pick whichever levels your goal fastest
		final List<CombatStyle> others = new ArrayList<>(CombatStyle.available(client));
		others.remove(style);
		if (!others.isEmpty() && hp != null)
		{
			final Line other = Line.detail().text("Other styles: ");
			boolean first = true;
			for (CombatStyle s : others)
			{
				final Map<Skill, XpRate> r = combat.rates(name, hp, s, null);
				final List<String> parts = new ArrayList<>();
				for (XpRate rate : r.values())
				{
					if (rate.skill == Skill.HITPOINTS)
					{
						continue;
					}
					final LevelMath.Progress p = progressFor(rate.skill, rate.xp);
					parts.add(shortSkill(rate.skill, r.size() > 2) + " " + (p.actionsRemaining < 0 ? "?" : fmt(p.actionsRemaining)));
				}
				if (parts.isEmpty())
				{
					continue;
				}
				if (!first)
				{
					other.text("; ");
				}
				first = false;
				other.strong(s.displayName).text(" " + String.join(", ", parts));
			}
			if (!first)
			{
				lines.add(other);
			}
		}
		return lines;
	}

	// ------------------------------------------------------------------
	// Goals
	// ------------------------------------------------------------------

	static final class Material
	{
		final int itemId;
		final String name;
		final long need;
		final int have;
		final long missing;
		/** How to get the missing ones, e.g. "Woodcutting 60, +21,350 xp", or null. */
		final String source;
		/** Ingredients of a missing intermediate, one level down. */
		final List<Material> parts;

		Material(int itemId, String name, long need, int have, String source, List<Material> parts)
		{
			this.itemId = itemId;
			this.name = name;
			this.need = need;
			this.have = have;
			this.missing = Math.max(0, need - have);
			this.source = source;
			this.parts = parts;
		}
	}

	static final class GoalView
	{
		final Goals.Goal goal;
		final String title;
		final boolean reached;
		/** -1 when the XP per action isn't known. */
		final int actionsLeft;
		final double xpEach;
		final int xpLeft;
		final long etaMillis;
		final List<Material> materials;
		final List<Line> lines;

		GoalView(Goals.Goal goal, String title, boolean reached, int actionsLeft, double xpEach, int xpLeft,
			long etaMillis, List<Material> materials, List<Line> lines)
		{
			this.goal = goal;
			this.title = title;
			this.reached = reached;
			this.actionsLeft = actionsLeft;
			this.xpEach = xpEach;
			this.xpLeft = xpLeft;
			this.etaMillis = etaMillis;
			this.materials = materials;
			this.lines = lines;
		}

		/** The first thing you're short of, for the overlay. */
		Material firstMissing()
		{
			for (Material m : materials)
			{
				if (m.missing > 0)
				{
					return m;
				}
			}
			return null;
		}
	}

	GoalView goal(Goals.Goal goal)
	{
		final Skill skill = goal.skill;
		final int target = Math.max(1, Math.min(Experience.MAX_VIRT_LEVEL, goal.targetLevel));
		final int currentXp = client.getSkillExperience(skill);
		final int xpLeft = Math.max(0, Experience.getXpForLevel(target) - currentXp);
		final String title = skill.getName() + " " + target + ": " + goal.name;

		final XpRate rate = goalRate(goal);
		final double xpEach = rate == null ? -1 : rate.xp;
		final int actions = rate == null ? -1 : LevelMath.actionsFor(xpLeft, xpEach);

		final List<Material> materials = new ArrayList<>();
		if (goal.kind == Goals.Goal.Kind.ITEM && rate != null && actions > 0)
		{
			if (rate.consumesItemId > 0)
			{
				materials.add(material(rate.consumesItemId, actions, true));
			}
			else
			{
				final RecipeBook.Recipe recipe = rates.recipeFor(goal.itemId, skill);
				if (recipe != null)
				{
					for (Map.Entry<Integer, Double> ing : recipe.perProduct.entrySet())
					{
						materials.add(material(ing.getKey(), (long) Math.ceil(actions * ing.getValue() - 1e-9), true));
					}
				}
			}
		}

		final TrackedAction pace = paceLookup.apply(skill, goal.name);
		final long eta = pace != null && actions > 0 ? pace.etaMillis(System.currentTimeMillis(), actions) : -1;

		final List<Line> lines = new ArrayList<>();
		final boolean reached = xpLeft == 0;
		if (reached)
		{
			lines.add(Line.main().strong("Reached!").text(" " + skill.getName() + " is level " + target + " or higher."));
		}
		else if (rate == null)
		{
			lines.add(Line.main().text(fmt(xpLeft) + " xp left. XP per " + (goal.kind == Goals.Goal.Kind.MONSTER ? "kill" : "action")
				+ " not known yet; do it once"));
		}
		else
		{
			final Line l = Line.main().strong(fmt(actions) + (goal.kind == Goals.Goal.Kind.MONSTER ? " kills" : " left"))
				.text(", " + xpFormat.format(xpEach) + " xp each, " + fmt(xpLeft) + " xp");
			if (eta >= 0)
			{
				l.text(", ~" + TrackedAction.formatDuration(eta));
			}
			lines.add(l);
		}

		for (Material m : materials)
		{
			lines.add(materialLine(m));
			for (Material part : m.parts)
			{
				final Line sub = materialLine(part);
				lines.add(Line.detail().text("    - ").text(sub.plain()));
				if (part.missing > 0)
				{
					lines.get(lines.size() - 1).warn();
				}
			}
		}
		if (!materials.isEmpty() && !bank.bankKnown())
		{
			lines.add(Line.detail().text("Open your bank once so these counts include it."));
		}

		return new GoalView(goal, title, reached, actions, xpEach, xpLeft, eta, materials, lines);
	}

	/**
	 * Everything your goals are short of that you'll have to gather yourself, added up
	 * (an intermediate you can make counts as its ingredients instead).
	 */
	List<Material> gatherList(List<GoalView> views)
	{
		final Map<Integer, long[]> missingById = new LinkedHashMap<>();
		final Map<Integer, Material> sample = new LinkedHashMap<>();
		for (GoalView v : views)
		{
			if (v.reached)
			{
				continue;
			}
			for (Material m : v.materials)
			{
				final List<Material> leaves = m.parts.isEmpty() ? Collections.singletonList(m) : m.parts;
				for (Material leaf : leaves)
				{
					if (leaf.missing > 0)
					{
						missingById.computeIfAbsent(leaf.itemId, k -> new long[1])[0] += leaf.missing;
						sample.putIfAbsent(leaf.itemId, leaf);
					}
				}
			}
		}

		final List<Material> out = new ArrayList<>();
		for (Map.Entry<Integer, long[]> e : missingById.entrySet())
		{
			final Material s = sample.get(e.getKey());
			final long missing = e.getValue()[0];
			final XpTable.Entry gather = rates.gatheredBy(e.getKey());
			final String source = gather != null
				? gather.skill.getName() + " " + gather.level + ", +" + fmt((long) (missing * gather.xp)) + " xp"
				: s.source;
			out.add(new Material(e.getKey(), s.name, missing, 0, source, Collections.emptyList()));
		}
		return out;
	}

	/** XP per action for a goal: the method's rate in the goal's skill. */
	private XpRate goalRate(Goals.Goal goal)
	{
		switch (goal.kind)
		{
			case ITEM:
				return rates.forItem(goal.itemId).get(goal.skill);
			case ACTION:
				return rates.forTarget(goal.name, true).get(goal.skill);
			case MONSTER:
			{
				final Integer hp = combat.hitpoints(null, goal.name);
				if (goal.skill == Skill.SLAYER)
				{
					return combat.slayerRate(goal.name, hp, null);
				}
				// Assume you'll use whichever style trains the goal's skill best
				XpRate best = null;
				for (CombatStyle style : CombatStyle.values())
				{
					final XpRate r = combat.rates(goal.name, hp, style, null).get(goal.skill);
					if (r != null && (best == null || r.xp > best.xp))
					{
						best = r;
					}
				}
				return best;
			}
			default:
				return null;
		}
	}

	private Material material(int itemId, long need, boolean expand)
	{
		final int have = bank.total(itemId);
		final long missing = Math.max(0, need - have);
		final List<Material> parts = new ArrayList<>();
		String source = null;

		final XpTable.Entry gather = rates.gatheredBy(itemId);
		if (gather != null)
		{
			source = gather.skill.getName() + " " + gather.level;
		}
		else
		{
			final RecipeBook.Recipe recipe = findAnyRecipe(itemId);
			if (recipe != null)
			{
				source = "make with " + recipe.skill.getName();
				if (expand && missing > 0)
				{
					for (Map.Entry<Integer, Double> ing : recipe.perProduct.entrySet())
					{
						parts.add(material(ing.getKey(), (long) Math.ceil(missing * ing.getValue() - 1e-9), false));
					}
				}
			}
		}
		return new Material(itemId, rates.itemName(itemId), need, have, source, parts);
	}

	private RecipeBook.Recipe findAnyRecipe(int itemId)
	{
		for (Skill s : new Skill[]{Skill.COOKING, Skill.HERBLORE, Skill.CRAFTING})
		{
			final RecipeBook.Recipe r = rates.recipeFor(itemId, s);
			if (r != null)
			{
				return r;
			}
		}
		for (Skill s : Skill.values())
		{
			final RecipeBook.Recipe r = rates.recipeFor(itemId, s);
			if (r != null && !r.perProduct.isEmpty())
			{
				return r;
			}
		}
		return null;
	}

	private Line materialLine(Material m)
	{
		final Line l = Line.detail().strong(m.name).text(": need " + fmt(m.need) + ", have " + fmt(m.have));
		if (m.missing > 0)
		{
			l.text(", missing " + fmt(m.missing)).warn();
			if (m.source != null)
			{
				l.text(" (" + m.source + ")");
			}
		}
		else
		{
			l.text(" (enough)");
		}
		return l;
	}

	// ------------------------------------------------------------------
	// Level ups
	// ------------------------------------------------------------------

	List<Line> levelUp(Skill skill, int level, XpTable xpTable)
	{
		final List<Line> lines = new ArrayList<>();
		final List<String> now = xpTable.unlocksAt(skill, level);
		final Line l = Line.main().strong(skill.getName() + " " + level + "!");
		if (!now.isEmpty())
		{
			l.text(" New: " + joinShort(now, 4) + ".");
		}
		final int next = xpTable.nextUnlockLevel(skill, level);
		if (next > 0)
		{
			l.text(" Next at " + next + ": " + joinShort(xpTable.unlocksAt(skill, next), 3) + ".");
		}
		lines.add(l);
		return lines;
	}

	private static String joinShort(List<String> names, int max)
	{
		if (names.size() <= max)
		{
			return String.join(", ", names);
		}
		return String.join(", ", names.subList(0, max)) + " and " + (names.size() - max) + " more";
	}

	// ------------------------------------------------------------------
	// Building blocks
	// ------------------------------------------------------------------

	/** "Iron ore: 112 more to Mining 42 (35 xp each), ~12m" */
	Line countLine(String name, XpRate rate)
	{
		final Skill skill = rate.skill;
		final LevelMath.Progress p = progressFor(skill, rate.xp);
		final Line l = Line.main().strong(name).text(rate.gives != null ? " (" + rate.gives + "): " : ": ");
		if (p.isMaxed())
		{
			l.text(skill.getName() + " is maxed");
		}
		else if (p.actionsRemaining < 0)
		{
			l.text(fmt(p.xpRemaining) + " xp to " + skill.getName() + " " + p.targetLevel);
		}
		else
		{
			l.strong(fmt(p.actionsRemaining) + " more")
				.text(" to " + skill.getName() + " " + p.targetLevel
					+ " (" + xpFormat.format(rate.xp) + " xp each" + (rate.measured ? ", measured" : "") + ")");
			appendEta(l, skill, name, rate.gives, p.actionsRemaining);
		}
		if (rate.levelRequired > client.getRealSkillLevel(skill))
		{
			l.text(" - needs level " + rate.levelRequired).warn();
		}
		return l;
	}

	/** "  Strength: 166 kills to 71 (464 xp each), ~1h 10m" */
	Line killLine(String monster, XpRate rate)
	{
		return killLine(monster, rate, false);
	}

	/**
	 * @param named true for "Hellhound: 166 kills to Strength 71 (...)", as a chat message on its own
	 */
	Line killLine(String monster, XpRate rate, boolean named)
	{
		final Skill skill = rate.skill;
		final LevelMath.Progress p = progressFor(skill, rate.xp);
		final Line l = named ? Line.main().strong(monster).text(": ") : Line.detail().text(skill.getName() + ": ");
		final String skillPart = named ? skill.getName() + " " : "";
		if (p.isMaxed())
		{
			l.text("maxed");
		}
		else if (p.actionsRemaining < 0)
		{
			l.text(fmt(p.xpRemaining) + " xp to " + skillPart + p.targetLevel);
		}
		else
		{
			l.strong(fmt(p.actionsRemaining) + (p.actionsRemaining == 1 ? " kill" : " kills"))
				.text(" to " + skillPart + p.targetLevel + " (" + xpFormat.format(rate.xp) + " xp each" + (rate.measured ? ", measured" : "") + ")");
			appendEta(l, skill, monster, null, p.actionsRemaining);
		}
		if (rate.note != null)
		{
			l.text(" - " + rate.note);
		}
		return l;
	}

	private void appendEta(Line l, Skill skill, String name, String gives, int actionsLeft)
	{
		TrackedAction pace = paceLookup.apply(skill, name);
		if (pace == null && gives != null)
		{
			pace = paceLookup.apply(skill, gives);
		}
		if (pace == null)
		{
			return;
		}
		final long eta = pace.etaMillis(System.currentTimeMillis(), actionsLeft);
		if (eta >= 0)
		{
			l.text(", ~" + TrackedAction.formatDuration(eta));
		}
	}

	/** "You have 340 (300 banked): Firemaking to 66 (1,240 xp short of 67)" */
	private void haveLines(List<Line> lines, XpRate rate, boolean brief)
	{
		final int itemId = rate.consumesItemId;
		final int banked = bank.inBank(itemId);
		final int have = bank.total(itemId);
		if (have == 0 && !bank.bankKnown())
		{
			// Nothing carried and the bank's never been seen: nothing useful to say yet
			return;
		}
		final Line l = Line.detail().text("You have ").strong(fmt(have));
		if (banked > 0 && banked != have)
		{
			l.text(" (" + fmt(banked) + " banked)");
		}
		l.text(": ");
		projectInto(l, rate.skill, have * rate.xp);

		// If this is one ingredient of several, the others may run out first
		for (RecipeBook.Recipe recipe : rates.recipesUsing(itemId, rate.skill))
		{
			for (Map.Entry<Integer, Double> ing : recipe.perProduct.entrySet())
			{
				if (ing.getKey() == itemId)
				{
					continue;
				}
				final int other = bank.total(ing.getKey());
				final double perThis = ing.getValue() / recipe.perProduct.get(itemId);
				if (other < have * perThis)
				{
					l.text(" - but only " + fmt(other) + " " + rates.itemName(ing.getKey())).warn();
				}
			}
			break;
		}
		lines.add(l);
	}

	/** For things you make: what each one uses, and how many you can make with what you have. */
	private void recipeLines(List<Line> lines, XpRate rate, RecipeBook.Recipe recipe, boolean brief)
	{
		final LevelMath.Progress p = progressFor(rate.skill, rate.xp);
		long canMake = Long.MAX_VALUE;
		String limitedBy = null;
		final List<String> uses = new ArrayList<>();
		for (Map.Entry<Integer, Double> ing : recipe.perProduct.entrySet())
		{
			final int have = bank.total(ing.getKey());
			final long n = (long) Math.floor(have / ing.getValue() + 1e-9);
			final String ingName = rates.itemName(ing.getKey());
			uses.add(qty(ing.getValue()) + " " + ingName + " (have " + fmt(have) + ")");
			if (n < canMake)
			{
				canMake = n;
				limitedBy = ingName;
			}
		}
		if (canMake == Long.MAX_VALUE)
		{
			return;
		}

		if (!brief)
		{
			lines.add(Line.detail().text("Each uses " + String.join(" + ", uses)));
		}
		final Line l = Line.detail().text("You can make ").strong(fmt(canMake)).text(": ");
		projectInto(l, rate.skill, canMake * rate.xp);
		if (p.actionsRemaining > canMake && recipe.perProduct.size() > 1 && limitedBy != null)
		{
			l.text(" (limited by " + limitedBy + ")").warn();
		}
		lines.add(l);
	}

	/** Gilded altar, Ectofuntus and Chaos altar for bones; Demonic offering for ashes. */
	private void prayerLines(List<Line> lines, String name, XpRate rate)
	{
		final String lower = name.toLowerCase(Locale.ROOT);
		final int have = bank.total(rate.consumesItemId);
		if (lower.contains("bones"))
		{
			altarLine(lines, "Gilded altar", rate, GILDED_ALTAR, 1.0, have);
			altarLine(lines, "Ectofuntus", rate, ECTOFUNTUS, 1.0, have);
			// Half the bones are saved on average, so each bone used is worth double
			altarLine(lines, "Chaos altar", rate, CHAOS_ALTAR, 2.0, have);
		}
		else if (lower.contains("ashes"))
		{
			altarLine(lines, "Demonic offering", rate, DEMONIC_OFFERING, 1.0, have);
		}
	}

	private void altarLine(List<Line> lines, String where, XpRate rate, double multiplier, double perItemFactor, int have)
	{
		final double xpEach = rate.xp * multiplier;
		final LevelMath.Progress p = progressFor(rate.skill, xpEach * perItemFactor);
		final Line l = Line.detail().text(where + ": ");
		if (p.actionsRemaining >= 0 && !p.isMaxed())
		{
			l.strong((perItemFactor > 1 ? "~" : "") + fmt(p.actionsRemaining) + " more")
				.text(" (" + xpFormat.format(xpEach) + " xp each" + (perItemFactor > 1 ? ", half saved" : "") + ")");
		}
		if (have > 0)
		{
			final LevelMath.Projection pr = LevelMath.project(client.getSkillExperience(rate.skill), have * xpEach * perItemFactor, config.virtualLevels());
			l.text("; your " + fmt(have) + ": ");
			if (pr.levelsUp())
			{
				l.text("level ").strong(String.valueOf(pr.toLevel));
			}
			else
			{
				l.text("not quite, " + fmt(pr.shortOfNext) + " xp short of " + (pr.toLevel + 1));
			}
		}
		lines.add(l);
	}

	/** "Firemaking to 66 (1,240 xp short of 67)" or "Slayer stays 60 (18,500 xp short of 61)" */
	private void projectInto(Line l, Skill skill, double gainXp)
	{
		final LevelMath.Projection pr = LevelMath.project(client.getSkillExperience(skill), gainXp, config.virtualLevels());
		if (pr.levelsUp())
		{
			l.text(skill.getName() + " to ").strong(String.valueOf(pr.toLevel));
			if (pr.shortOfNext > 0)
			{
				l.text(" (" + fmt(pr.shortOfNext) + " xp short of " + (pr.toLevel + 1) + ")");
			}
		}
		else
		{
			l.text(skill.getName() + " stays " + pr.toLevel + " (" + fmt(pr.shortOfNext) + " xp short of " + (pr.toLevel + 1) + ")");
		}
	}

	LevelMath.Progress progressFor(Skill skill, double xpEach)
	{
		return LevelMath.progress(client.getSkillExperience(skill), config.targetLevel(), config.virtualLevels(), xpEach);
	}

	String formatXp(double xp)
	{
		return xpFormat.format(xp);
	}

	private static String shortSkill(Skill skill, boolean abbreviate)
	{
		if (!abbreviate)
		{
			return skill.getName();
		}
		switch (skill)
		{
			case ATTACK:
				return "Att";
			case STRENGTH:
				return "Str";
			case DEFENCE:
				return "Def";
			default:
				return skill.getName();
		}
	}

	private static String qty(double q)
	{
		return Math.abs(q - Math.rint(q)) < 1e-6 ? String.valueOf((long) Math.rint(q)) : String.format(Locale.ROOT, "%.2f", q);
	}

	static String fmt(long n)
	{
		return String.format(Locale.ROOT, "%,d", n);
	}

}
