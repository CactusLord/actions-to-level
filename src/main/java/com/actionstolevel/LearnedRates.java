package com.actionstolevel;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * XP-per-item and XP-per-interaction values seen while playing. These pick up anything
 * the base table can't know about: outfit bonuses, altars, Wintertodt-style methods,
 * and items the table doesn't list at all.
 */
final class LearnedRates
{
	/** Observations kept before older ones start being faded out. */
	private static final int MAX_SAMPLES = 40;
	/** A new value this far from the average means the method changed, so start over. */
	private static final double RESET_DEVIATION = 0.30;

	static final class Rate
	{
		double xpSum;
		int samples;

		Rate(double xpSum, int samples)
		{
			this.xpSum = xpSum;
			this.samples = samples;
		}

		double average()
		{
			return samples == 0 ? 0 : xpSum / samples;
		}
	}

	private final Map<String, EnumMap<Skill, Rate>> rates = new HashMap<>();
	private boolean dirty;

	static String itemKey(int itemId)
	{
		return "i:" + itemId;
	}

	static String targetKey(String targetName)
	{
		return "t:" + clean(targetName).toLowerCase(Locale.ROOT);
	}

	void record(String key, Skill skill, double xp)
	{
		if (xp <= 0 || Double.isNaN(xp) || Double.isInfinite(xp))
		{
			return;
		}

		final EnumMap<Skill, Rate> bySkill = rates.computeIfAbsent(key, k -> new EnumMap<>(Skill.class));
		final Rate rate = bySkill.get(skill);
		if (rate == null)
		{
			bySkill.put(skill, new Rate(xp, 1));
		}
		else
		{
			final double avg = rate.average();
			if (avg > 0 && Math.abs(xp - avg) / avg > RESET_DEVIATION)
			{
				rate.xpSum = xp;
				rate.samples = 1;
			}
			else
			{
				rate.xpSum += xp;
				rate.samples++;
				if (rate.samples > MAX_SAMPLES)
				{
					final int keep = MAX_SAMPLES / 2;
					rate.xpSum = rate.average() * keep;
					rate.samples = keep;
				}
			}
		}
		dirty = true;
	}

	/**
	 * Running average with no reset, for values that are noisy per sample but steady over
	 * time, like combat XP per kill when several monsters die close together.
	 */
	void recordAverage(String key, Skill skill, double xp)
	{
		if (xp <= 0 || Double.isNaN(xp) || Double.isInfinite(xp))
		{
			return;
		}

		final EnumMap<Skill, Rate> bySkill = rates.computeIfAbsent(key, k -> new EnumMap<>(Skill.class));
		final Rate rate = bySkill.get(skill);
		if (rate == null)
		{
			bySkill.put(skill, new Rate(xp, 1));
		}
		else
		{
			rate.xpSum += xp;
			rate.samples++;
			if (rate.samples > MAX_SAMPLES)
			{
				final int keep = MAX_SAMPLES / 2;
				rate.xpSum = rate.average() * keep;
				rate.samples = keep;
			}
		}
		dirty = true;
	}

	Map<Skill, Rate> get(String key)
	{
		final EnumMap<Skill, Rate> bySkill = rates.get(key);
		return bySkill == null ? Collections.emptyMap() : bySkill;
	}

	Rate get(String key, Skill skill)
	{
		return get(key).get(skill);
	}

	boolean isDirty()
	{
		return dirty;
	}

	void clear()
	{
		rates.clear();
		dirty = true;
	}

	/** One rate per line: key, skill, xp sum, sample count — tab separated. */
	String serialize()
	{
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, EnumMap<Skill, Rate>> e : rates.entrySet())
		{
			for (Map.Entry<Skill, Rate> r : e.getValue().entrySet())
			{
				sb.append(e.getKey()).append('\t')
					.append(r.getKey().name()).append('\t')
					.append(String.format(Locale.ROOT, "%.3f", r.getValue().xpSum)).append('\t')
					.append(r.getValue().samples).append('\n');
			}
		}
		dirty = false;
		return sb.toString();
	}

	void deserialize(String data)
	{
		rates.clear();
		dirty = false;
		if (data == null || data.isEmpty())
		{
			return;
		}

		for (String line : data.split("\n"))
		{
			final String[] parts = line.split("\t");
			if (parts.length != 4)
			{
				continue;
			}
			try
			{
				final Skill skill = Skill.valueOf(parts[1]);
				final double sum = Double.parseDouble(parts[2]);
				final int samples = Integer.parseInt(parts[3]);
				if (samples > 0 && sum > 0)
				{
					rates.computeIfAbsent(parts[0], k -> new EnumMap<>(Skill.class)).put(skill, new Rate(sum, samples));
				}
			}
			catch (IllegalArgumentException ignored)
			{
				// Unknown skill or a corrupt number; drop the line
			}
		}
	}

	private static String clean(String s)
	{
		return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').trim();
	}
}
