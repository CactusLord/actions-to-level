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

	private final ActionsToLevelPlugin plugin;
	private final ActionsToLevelConfig config;

	@Inject
	ActionsToLevelOverlay(ActionsToLevelPlugin plugin, ActionsToLevelConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		panelComponent.setPreferredSize(new Dimension(170, 0));
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
		if (actions.isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Actions to level")
			.build());

		for (TrackedAction action : actions)
		{
			final LevelMath.Progress p = plugin.progressFor(action.skill, action.xpEach);
			final String count;
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

			panelComponent.getChildren().add(LineComponent.builder()
				.left(left)
				.leftColor(underLevel ? WARN : SUBTLE)
				.right(plugin.formatXp(action.xpEach) + " xp")
				.rightColor(SUBTLE)
				.build());
		}

		return super.render(graphics);
	}
}
