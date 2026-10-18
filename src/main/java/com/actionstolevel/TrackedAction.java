package com.actionstolevel;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import net.runelite.api.Skill;

/**
 * One line on the overlay: an item, object or monster you've recently gained XP from.
 * The "how many left" number is worked out fresh from your current XP each frame; this
 * also keeps your recent pace so it can estimate the time to level.
 */
final class TrackedAction
{
	/** Pace is measured over this much recent activity. */
	static final long PACE_WINDOW_MS = 15 * 60 * 1000L;
	/** Need at least this much activity before showing a pace. */
	private static final long MIN_SPAN_MS = 20 * 1000L;
	private static final int MIN_EVENTS = 3;

	final Skill skill;
	final String name;
	double xpEach;
	int levelRequired;
	/** True when each action is a kill. */
	boolean perKill;
	/** Extra text for the second line, e.g. "85 left" on a Slayer task. */
	String note;
	Instant lastUpdated;

	/** Actions done since this line first appeared this session. */
	int sessionCount;
	double sessionXp;
	/** Target level the "almost there" alert last fired for. */
	int alertedForLevel = -1;

	/** {time millis, actions} for recent drops. */
	private final Deque<long[]> events = new ArrayDeque<>();

	TrackedAction(Skill skill, String name)
	{
		this.skill = skill;
		this.name = name;
	}

	static String key(Skill skill, String name)
	{
		return skill.name() + '|' + name;
	}

	void recordActions(long nowMillis, int actions, double xp)
	{
		if (actions <= 0)
		{
			return;
		}
		sessionCount += actions;
		sessionXp += xp;
		events.addLast(new long[]{nowMillis, actions});
		while (!events.isEmpty() && nowMillis - events.peekFirst()[0] > PACE_WINDOW_MS)
		{
			events.removeFirst();
		}
	}

	/** Actions per hour over recent activity, or -1 if there isn't enough yet. */
	double perHour(long nowMillis)
	{
		if (events.size() < MIN_EVENTS)
		{
			return -1;
		}
		final long first = events.peekFirst()[0];
		final long last = events.peekLast()[0];
		final long span = last - first;
		// Stale: you stopped a while ago
		if (span < MIN_SPAN_MS || nowMillis - last > PACE_WINDOW_MS)
		{
			return -1;
		}
		long actions = 0;
		boolean skipFirst = true;
		for (long[] e : events)
		{
			// The first event marks the start of the window, so its actions happened before it
			if (skipFirst)
			{
				skipFirst = false;
				continue;
			}
			actions += e[1];
		}
		return actions * 3_600_000.0 / span;
	}

	/** Estimated milliseconds to do this many more actions, or -1 if unknown. */
	long etaMillis(long nowMillis, int actionsLeft)
	{
		final double perHour = perHour(nowMillis);
		if (perHour <= 0 || actionsLeft < 0)
		{
			return -1;
		}
		return (long) (actionsLeft / perHour * 3_600_000.0);
	}

	/** "38m", "2h 05m", "45s" */
	static String formatDuration(long millis)
	{
		if (millis < 0)
		{
			return "";
		}
		final long totalMinutes = Math.round(millis / 60_000.0);
		if (millis < 60_000)
		{
			return Math.max(1, millis / 1000) + "s";
		}
		if (totalMinutes < 60)
		{
			return totalMinutes + "m";
		}
		return String.format("%dh %02dm", totalMinutes / 60, totalMinutes % 60);
	}
}
