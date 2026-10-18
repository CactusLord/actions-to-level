package com.actionstolevel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * Side panel: look anything up, keep goals with what they need from your bank, see what's left
 * to gather, your Slayer task, and this session's pace.
 */
class ActionsToLevelPanel extends PluginPanel
{
	private static final Color WARN = new Color(255, 120, 90);
	private static final Color MUTED = new Color(165, 165, 165);
	private static final int TEXT_WIDTH = PANEL_WIDTH - 30;

	/** What a "+ goal" button adds. */
	static final class GoalSpec
	{
		final Skill skill;
		final Goals.Goal.Kind kind;
		final int itemId;
		final String name;

		GoalSpec(Skill skill, Goals.Goal.Kind kind, int itemId, String name)
		{
			this.skill = skill;
			this.kind = kind;
			this.itemId = itemId;
			this.name = name;
		}
	}

	static final class Card
	{
		final String title;
		final List<Line> lines;
		final List<GoalSpec> goalButtons;
		/** Set for goal cards. */
		final long goalId;
		final int targetLevel;
		final boolean done;

		Card(String title, List<Line> lines, List<GoalSpec> goalButtons, long goalId, int targetLevel, boolean done)
		{
			this.title = title;
			this.lines = lines;
			this.goalButtons = goalButtons;
			this.goalId = goalId;
			this.targetLevel = targetLevel;
			this.done = done;
		}

		static Card info(String title, List<Line> lines, List<GoalSpec> goalButtons)
		{
			return new Card(title, lines, goalButtons, -1, 0, false);
		}
	}

	static final class Data
	{
		List<Card> results = Collections.emptyList();
		List<Card> goals = Collections.emptyList();
		List<Line> gather = Collections.emptyList();
		List<Line> slayer = Collections.emptyList();
		List<Line> session = Collections.emptyList();
		String query = "";
	}

	private final IconTextField search = new IconTextField();
	private final JSpinner newGoalTarget = new JSpinner(new SpinnerNumberModel(0, 0, 126, 1));
	private final JPanel content = new JPanel();
	private final Timer searchDebounce;
	private String lastSignature = "";

	private Consumer<String> onSearch = q -> { };
	private BiConsumer<GoalSpec, Integer> onAddGoal = (s, t) -> { };
	private Consumer<Long> onRemoveGoal = id -> { };
	private BiConsumer<Long, Integer> onGoalTarget = (id, t) -> { };

	ActionsToLevelPanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);

		search.setPreferredSize(new Dimension(PANEL_WIDTH - 16, 30));
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		search.setToolTipText("Search items, actions and monsters you've fought");
		top.add(search);
		top.add(Box.createVerticalStrut(6));

		final JPanel targetRow = new JPanel(new BorderLayout());
		targetRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final JLabel targetLabel = new JLabel("New goal level (0 = next level)");
		targetLabel.setForeground(MUTED);
		targetRow.add(targetLabel, BorderLayout.CENTER);
		newGoalTarget.setPreferredSize(new Dimension(56, 24));
		targetRow.add(newGoalTarget, BorderLayout.EAST);
		top.add(targetRow);
		top.add(Box.createVerticalStrut(8));
		add(top, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.add(content, BorderLayout.NORTH);
		add(wrap, BorderLayout.CENTER);

		searchDebounce = new Timer(300, e -> onSearch.accept(search.getText().trim()));
		searchDebounce.setRepeats(false);
		search.addKeyListener(new java.awt.event.KeyAdapter()
		{
			@Override
			public void keyReleased(java.awt.event.KeyEvent e)
			{
				searchDebounce.restart();
			}
		});
		search.addClearListener(() -> onSearch.accept(""));

		render(new Data());
	}

	void setCallbacks(Consumer<String> onSearch, BiConsumer<GoalSpec, Integer> onAddGoal,
		Consumer<Long> onRemoveGoal, BiConsumer<Long, Integer> onGoalTarget)
	{
		this.onSearch = onSearch;
		this.onAddGoal = onAddGoal;
		this.onRemoveGoal = onRemoveGoal;
		this.onGoalTarget = onGoalTarget;
	}

	String query()
	{
		return search.getText().trim();
	}

	/** Opening the panel refreshes it straight away. */
	@Override
	public void onActivate()
	{
		onSearch.accept(query());
	}

	/** Called from any thread with fresh data; redraws on the Swing thread only if something changed. */
	void update(Data data)
	{
		final String signature = signature(data);
		SwingUtilities.invokeLater(() ->
		{
			if (signature.equals(lastSignature))
			{
				return;
			}
			lastSignature = signature;
			render(data);
		});
	}

	private static String signature(Data data)
	{
		final StringBuilder sb = new StringBuilder(data.query).append('\u0001');
		for (List<Card> cards : java.util.Arrays.asList(data.results, data.goals))
		{
			for (Card c : cards)
			{
				sb.append(c.title).append(c.goalId).append(c.targetLevel).append(c.done).append(c.goalButtons.size());
				for (Line l : c.lines)
				{
					sb.append(l).append(l.warning);
				}
			}
			sb.append('\u0002');
		}
		for (List<Line> lines : java.util.Arrays.asList(data.gather, data.slayer, data.session))
		{
			for (Line l : lines)
			{
				sb.append(l).append(l.warning);
			}
			sb.append('\u0003');
		}
		return sb.toString();
	}

	private void render(Data data)
	{
		content.removeAll();

		if (!data.query.isEmpty())
		{
			section("Results");
			if (data.results.isEmpty())
			{
				content.add(text(Line.detail().text("Nothing found. Try an item, a rock or tree, or a monster you've fought."), MUTED));
			}
			for (Card card : data.results)
			{
				content.add(card(card));
			}
		}

		section("Goals");
		if (data.goals.isEmpty())
		{
			content.add(text(Line.detail().text("No goals yet. Search above and press + on a skill, or hold Shift and right-click something in game."), MUTED));
		}
		for (Card card : data.goals)
		{
			content.add(card(card));
		}

		if (!data.gather.isEmpty())
		{
			section("To gather");
			content.add(lines(data.gather));
		}

		if (!data.slayer.isEmpty())
		{
			section("Slayer task");
			content.add(lines(data.slayer));
		}

		if (!data.session.isEmpty())
		{
			section("This session");
			content.add(lines(data.session));
		}

		content.revalidate();
		content.repaint();
	}

	private void section(String title)
	{
		final JLabel label = new JLabel(title);
		label.setForeground(Color.WHITE);
		label.setFont(label.getFont().deriveFont(Font.BOLD));
		label.setBorder(new EmptyBorder(8, 0, 4, 0));
		label.setAlignmentX(LEFT_ALIGNMENT);
		content.add(label);
	}

	private JPanel card(Card card)
	{
		final JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 4, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(6, 6, 6, 6)));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		final JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		final JLabel title = new JLabel(html("<b>" + escape(card.title) + "</b>"));
		title.setForeground(card.done ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE);
		header.add(title, BorderLayout.CENTER);

		if (card.goalId >= 0)
		{
			final JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
			controls.setOpaque(false);
			final JSpinner target = new JSpinner(new SpinnerNumberModel(Math.max(1, Math.min(126, card.targetLevel)), 1, 126, 1));
			target.setPreferredSize(new Dimension(48, 22));
			target.setToolTipText("Target level");
			target.addChangeListener(e -> onGoalTarget.accept(card.goalId, (Integer) target.getValue()));
			controls.add(target);
			final JButton remove = smallButton("x", "Remove goal");
			remove.addActionListener(e -> onRemoveGoal.accept(card.goalId));
			controls.add(remove);
			header.add(controls, BorderLayout.EAST);
		}
		panel.add(header, BorderLayout.NORTH);
		panel.add(lines(card.lines), BorderLayout.CENTER);

		if (!card.goalButtons.isEmpty())
		{
			final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
			buttons.setOpaque(false);
			for (GoalSpec spec : card.goalButtons)
			{
				final JButton add = smallButton("+ " + spec.skill.getName(), "Add a " + spec.skill.getName() + " goal for " + spec.name);
				add.addActionListener(e -> onAddGoal.accept(spec, (Integer) newGoalTarget.getValue()));
				buttons.add(add);
			}
			panel.add(buttons, BorderLayout.SOUTH);
		}
		return panel;
	}

	private JPanel lines(List<Line> lines)
	{
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setAlignmentX(LEFT_ALIGNMENT);
		for (Line line : lines)
		{
			panel.add(text(line, line.warning ? WARN : (line.detail ? MUTED : ColorScheme.TEXT_COLOR)));
		}
		return panel;
	}

	private static JLabel text(Line line, Color color)
	{
		final JLabel label = new JLabel(html(line.html()));
		label.setForeground(color);
		label.setBorder(new EmptyBorder(1, line.detail ? 6 : 0, 1, 0));
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private static JButton smallButton(String text, String tooltip)
	{
		final JButton b = new JButton(text);
		b.setToolTipText(tooltip);
		b.setFocusable(false);
		b.setMargin(new java.awt.Insets(1, 4, 1, 4));
		return b;
	}

	private static String html(String body)
	{
		return "<html><div style='width:" + TEXT_WIDTH + "px'>" + body + "</div></html>";
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
