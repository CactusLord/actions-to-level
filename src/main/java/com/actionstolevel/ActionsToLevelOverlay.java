package com.actionstolevel;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class ActionsToLevelOverlay extends OverlayPanel
{
	private static final Color SUBTLE = new Color(190, 190, 190);
	private static final Color COUNT = new Color(255, 200, 0);
	private static final Color WARN = new Color(255, 120, 90);
	private static final Color DONE = new Color(90, 220, 110);

	private final ActionsToLevelPlugin plugin;
	private final ActionsToLevelConfig config;

	@Inject
	ActionsToLevelOverlay(ActionsToLevelPlugin plugin, ActionsToLevelConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		panelComponent.setPreferredSize(new Dimension(190, 0));
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Clear", "Actions to level", e -> plugin.clearTracked());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}

		final List<TrackedAction> actions = plugin.getTrackedNewestFirst();
		final List<Reports.GoalView> goals = config.showGoalsOnOverlay() ? plugin.overlayGoals() : java.util.Collections.emptyList();
		if (actions.isEmpty() && goals.isEmpty())
		{
			return null;
		}

		final long now = System.currentTimeMillis();
		if (!actions.isEmpty())
		{
			panelComponent.getChildren().add(TitleComponent.builder()
				.text("Actions to level")
				.build());
		}

		for (TrackedAction action : actions)
		{
			final LevelMath.Progress p = plugin.progressFor(action.skill, action.xpEach);
			String count;
			if (p.isMaxed())
			{
				count = "Max";
			}
			else if (p.actionsRemaining < 0)
			{
				count = "?";
			}
			else
			{
				count = String.format("%,d", p.actionsRemaining);
				if (config.showTimeToLevel())
				{
					final long eta = action.etaMillis(now, p.actionsRemaining);
					if (eta >= 0)
					{
						count += " ~" + TrackedAction.formatDuration(eta);
					}
				}
			}

			panelComponent.getChildren().add(LineComponent.builder()
				.left(action.name)
				.right(count)
				.rightColor(COUNT)
				.build());

			final int shownLevel = config.virtualLevels() ? p.currentLevel : Math.min(p.currentLevel, 99);
			final String goal = p.isMaxed()
				? action.skill.getName() + " " + shownLevel
				: action.skill.getName() + " " + shownLevel + " > " + p.targetLevel;
			final boolean underLevel = action.levelRequired > p.currentLevel;

			String left = underLevel ? goal + " (needs " + action.levelRequired + ")" : goal;
			if (action.note != null)
			{
				left += " (" + action.note + ")";
			}

			String right = plugin.formatXp(action.xpEach) + " xp";
			if (config.showPerHour())
			{
				final double perHour = action.perHour(now);
				if (perHour > 0)
				{
					right = String.format("%,d/h", Math.round(perHour));
				}
			}

			panelComponent.getChildren().add(LineComponent.builder()
				.left(left)
				.leftColor(underLevel ? WARN : SUBTLE)
				.right(right)
				.rightColor(SUBTLE)
				.build());
		}

		if (!goals.isEmpty())
		{
			panelComponent.getChildren().add(TitleComponent.builder()
				.text("Goals")
				.build());
			for (Reports.GoalView g : goals)
			{
				final String count;
				if (g.reached)
				{
					count = "Done";
				}
				else if (g.actionsLeft < 0)
				{
					count = "?";
				}
				else
				{
					count = String.format("%,d", g.actionsLeft) + (g.etaMillis >= 0 ? " ~" + TrackedAction.formatDuration(g.etaMillis) : "");
				}
				panelComponent.getChildren().add(LineComponent.builder()
					.left(g.goal.skill.getName() + " " + g.goal.targetLevel + ": " + g.goal.name)
					.right(count)
					.rightColor(g.reached ? DONE : COUNT)
					.build());

				final Reports.Material missing = g.firstMissing();
				if (missing != null && !g.reached)
				{
					panelComponent.getChildren().add(LineComponent.builder()
						.left("Need " + String.format("%,d", missing.missing) + " " + missing.name)
						.leftColor(WARN)
						.build());
				}
			}
		}

		return super.render(graphics);
	}
}
