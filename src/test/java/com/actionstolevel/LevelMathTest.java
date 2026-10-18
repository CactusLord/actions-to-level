package com.actionstolevel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import net.runelite.api.Experience;
import org.junit.Test;

public class LevelMathTest
{
	@Test
	public void ironOreAtLevel41()
	{
		// Fresh level 41 miner on iron (35 xp each): XP left to 42, divided by 35, rounded up
		final int xp = Experience.getXpForLevel(41);
		final LevelMath.Progress p = LevelMath.progress(xp, 0, false, 35);
		assertEquals(41, p.currentLevel);
		assertEquals(42, p.targetLevel);
		assertEquals(Experience.getXpForLevel(42) - xp, p.xpRemaining);
		assertEquals((int) Math.ceil(p.xpRemaining / 35.0), p.actionsRemaining);
	}

	@Test
	public void exactDivisionDoesNotRoundUp()
	{
		assertEquals(60, LevelMath.actionsFor(1050, 17.5));
		assertEquals(61, LevelMath.actionsFor(1051, 17.5));
		assertEquals(0, LevelMath.actionsFor(0, 17.5));
		assertEquals(-1, LevelMath.actionsFor(100, 0));
	}

	@Test
	public void configuredGoal()
	{
		final LevelMath.Progress p = LevelMath.progress(Experience.getXpForLevel(50), 60, false, 100);
		assertEquals(60, p.targetLevel);
		// Goal already passed -> falls back to next level
		final LevelMath.Progress passed = LevelMath.progress(Experience.getXpForLevel(70), 60, false, 100);
		assertEquals(71, passed.targetLevel);
	}

	@Test
	public void maxedWithoutVirtualLevels()
	{
		assertTrue(LevelMath.progress(Experience.getXpForLevel(99), 0, false, 100).isMaxed());
		assertEquals(100, LevelMath.progress(Experience.getXpForLevel(99), 0, true, 100).targetLevel);
		assertTrue(LevelMath.progress(Experience.MAX_SKILL_XP, 0, true, 100).isMaxed());
	}
}
