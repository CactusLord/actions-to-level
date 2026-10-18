package com.actionstolevel;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ParamID;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The player's current attack style and how much XP each point of damage gives.
 * <p>
 * Reading the style follows RuneLite's Attack Styles plugin (Copyright (c) 2017, honeyhoney,
 * BSD 2-Clause), which mirrors the game's own script 4525.
 */
enum CombatStyle
{
	ACCURATE("Accurate", Skill.ATTACK, 4),
	AGGRESSIVE("Aggressive", Skill.STRENGTH, 4),
	DEFENSIVE("Defensive", Skill.DEFENCE, 4),
	CONTROLLED("Controlled", Skill.ATTACK, 4 / 3.0, Skill.STRENGTH, 4 / 3.0, Skill.DEFENCE, 4 / 3.0),
	RANGING("Ranging", Skill.RANGED, 4),
	LONGRANGE("Longrange", Skill.RANGED, 2, Skill.DEFENCE, 2),
	CASTING("Casting", Skill.MAGIC, 2),
	DEFENSIVE_CASTING("Defensive casting", Skill.MAGIC, 4 / 3.0, Skill.DEFENCE, 1),
	OTHER("Unknown style");

	/** Every style also gives this much Hitpoints XP per damage. */
	static final double HITPOINTS_XP_PER_DAMAGE = 4 / 3.0;

	final String displayName;
	/** XP per point of damage for each skill the style trains (Hitpoints not included). */
	final Map<Skill, Double> xpPerDamage;

	CombatStyle(String displayName, Object... skillsAndRates)
	{
		this.displayName = displayName;
		final Map<Skill, Double> rates = new EnumMap<>(Skill.class);
		for (int i = 0; i + 1 < skillsAndRates.length; i += 2)
		{
			rates.put((Skill) skillsAndRates[i], ((Number) skillsAndRates[i + 1]).doubleValue());
		}
		this.xpPerDamage = Collections.unmodifiableMap(rates);
	}

	boolean isKnown()
	{
		return this != OTHER;
	}

	/** Must be called on the client thread. */
	static CombatStyle current(Client client)
	{
		final int weaponType = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
		int styleIndex = client.getVarpValue(VarPlayerID.COM_MODE);
		final int castingMode = client.getVarbitValue(VarbitID.AUTOCAST_DEFMODE);

		final CombatStyle[] styles = stylesFor(client, weaponType);
		// Staves only use slots 0-4; defensive autocast is slot 4 + 1
		if (styleIndex == 4)
		{
			styleIndex += castingMode;
		}
		if (styleIndex < 0 || styleIndex >= styles.length || styles[styleIndex] == null)
		{
			return OTHER;
		}
		return styles[styleIndex];
	}

	private static CombatStyle[] stylesFor(Client client, int weaponType)
	{
		final EnumComposition weaponStyles = client.getEnum(EnumID.WEAPON_STYLES);
		final int styleEnumId = weaponStyles == null ? -1 : weaponStyles.getIntValue(weaponType);
		if (styleEnumId == -1)
		{
			if (weaponType == 22)
			{
				// Blue moon spear
				return new CombatStyle[]{ACCURATE, AGGRESSIVE, null, DEFENSIVE, CASTING, DEFENSIVE_CASTING};
			}
			if (weaponType == 30)
			{
				// Partisan
				return new CombatStyle[]{ACCURATE, AGGRESSIVE, AGGRESSIVE, DEFENSIVE};
			}
			return new CombatStyle[0];
		}

		final EnumComposition styleEnum = client.getEnum(styleEnumId);
		if (styleEnum == null)
		{
			return new CombatStyle[0];
		}

		final int[] structs = styleEnum.getIntVals();
		final CombatStyle[] styles = new CombatStyle[structs.length];
		int i = 0;
		for (int structId : structs)
		{
			final StructComposition struct = client.getStructComposition(structId);
			final String name = struct == null ? null : struct.getStringValue(ParamID.ATTACK_STYLE_NAME);
			CombatStyle style = parse(name);
			if (style == OTHER)
			{
				// "Other" marks an unused slot
				++i;
				continue;
			}
			// "Defensive" in slot 5 is defensive casting
			if (i == 5 && style == DEFENSIVE)
			{
				style = DEFENSIVE_CASTING;
			}
			if (i < styles.length)
			{
				styles[i] = style;
			}
			i++;
		}
		return styles;
	}

	private static CombatStyle parse(String name)
	{
		if (name == null)
		{
			return OTHER;
		}
		try
		{
			return valueOf(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
		}
		catch (IllegalArgumentException e)
		{
			return OTHER;
		}
	}
}
