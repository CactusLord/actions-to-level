package com.actionstolevel;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.util.ColorUtil;

/**
 * One line of a report, made of plain and highlighted pieces. The same line is shown in the
 * chatbox, in a hover tooltip and in the side panel, each with its own styling.
 */
final class Line
{
	private final List<String> parts = new ArrayList<>();
	private final List<Boolean> strong = new ArrayList<>();
	/** Detail lines are indented in chat and smaller in the panel. */
	final boolean detail;
	/** Warnings (missing materials, level too low) are coloured in the panel and tooltip. */
	boolean warning;

	private Line(boolean detail)
	{
		this.detail = detail;
	}

	static Line main()
	{
		return new Line(false);
	}

	static Line detail()
	{
		return new Line(true);
	}

	Line text(String s)
	{
		if (s != null && !s.isEmpty())
		{
			parts.add(s);
			strong.add(false);
		}
		return this;
	}

	Line strong(String s)
	{
		if (s != null && !s.isEmpty())
		{
			parts.add(s);
			strong.add(true);
		}
		return this;
	}

	Line warn()
	{
		warning = true;
		return this;
	}

	boolean isEmpty()
	{
		return parts.isEmpty();
	}

	String plain()
	{
		return String.join("", parts);
	}

	String chat()
	{
		final ChatMessageBuilder b = new ChatMessageBuilder();
		if (detail)
		{
			b.append(ChatColorType.NORMAL).append("  ");
		}
		for (int i = 0; i < parts.size(); i++)
		{
			b.append(strong.get(i) ? ChatColorType.HIGHLIGHT : ChatColorType.NORMAL).append(parts.get(i));
		}
		return b.build();
	}

	String tooltip(Color strongColor, Color warnColor)
	{
		final StringBuilder b = new StringBuilder();
		for (int i = 0; i < parts.size(); i++)
		{
			final String p = parts.get(i);
			if (strong.get(i))
			{
				b.append(ColorUtil.wrapWithColorTag(p, strongColor));
			}
			else if (warning)
			{
				b.append(ColorUtil.wrapWithColorTag(p, warnColor));
			}
			else
			{
				b.append(p);
			}
		}
		return b.toString();
	}

	String html()
	{
		final StringBuilder b = new StringBuilder();
		for (int i = 0; i < parts.size(); i++)
		{
			final String p = escape(parts.get(i));
			b.append(strong.get(i) ? "<b>" + p + "</b>" : p);
		}
		return b.toString();
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	@Override
	public String toString()
	{
		return (detail ? "  " : "") + plain();
	}
}
