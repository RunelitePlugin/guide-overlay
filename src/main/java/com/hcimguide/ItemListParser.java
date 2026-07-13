package com.hcimguide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts item requirement lists from step text.
 *
 * "Withdraw: Coins, Air Runes, Bread (8 Inventory slots)" -&gt; [Coins, Air Runes, Bread]
 * "Collect 3x Logs [Tree Gnome Village]"                  -&gt; [3x Logs]
 */
public final class ItemListParser
{
	private static final Pattern WITHDRAW = Pattern.compile("(?i)^withdraw:?\\s+(.*)$");
	private static final Pattern GATHER_VERB = Pattern.compile("(?i)^(?:collect|take|buy|loot|pick up)\\s+(?:the\\s+|a\\s+|an\\s+)?(.*)$");
	private static final Pattern QTY_PREFIX = Pattern.compile("(?i)^(\\d+)\\s*x?\\s+(.*)$");

	// stop the item name at travel/location phrasing
	private static final Pattern GATHER_CUTOFF = Pattern.compile(
		"(?i)\\s+(?:off|from|inside|outside|at|on|next to|near|in|to|south|north|east|west|whenever|if|while|then|and wield|and equip|by)\\b.*$");

	private static final Set<String> NOT_ITEMS = new HashSet<>(Arrays.asList(
		"everything", "all", "it", "them", "both", "one", "this", "these"));

	private ItemListParser()
	{
	}

	/**
	 * Full multi-item list from a "Withdraw:" step, or null when this isn't one.
	 */
	public static List<ItemReq> parseWithdraw(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = WITHDRAW.matcher(text.trim());
		if (!m.matches())
		{
			return null;
		}
		String list = m.group(1)
			// drop trailing "(8 Inventory slots)" style notes
			.replaceAll("\\s*\\([^)]*\\)\\s*$", "")
			.replaceAll("[.\\s]+$", "");

		List<ItemReq> out = new ArrayList<>();
		for (String part : list.split(",|&"))
		{
			String p = part.trim();
			if (p.isEmpty())
			{
				continue;
			}
			int qty = 1;
			Matcher q = QTY_PREFIX.matcher(p);
			if (q.matches())
			{
				try
				{
					qty = Math.max(1, Integer.parseInt(q.group(1)));
				}
				catch (NumberFormatException ignored)
				{
				}
				p = q.group(2).trim();
			}
			// length cap: real item names are short; oversized "names" are
			// malformed wiki text and would only bloat tooltips/grids
			if (!p.isEmpty() && p.length() <= 60 && !NOT_ITEMS.contains(p.toLowerCase(java.util.Locale.ROOT)))
			{
				out.add(new ItemReq(p, qty));
			}
		}
		return out.isEmpty() ? null : out;
	}

	/**
	 * Single item from a gathering step ("Collect 3x Logs ..."), or null.
	 * Only applies when the step STARTS with the verb, to avoid false positives
	 * in longer sentences.
	 */
	public static List<ItemReq> parseGather(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = GATHER_VERB.matcher(text.trim());
		if (!m.matches())
		{
			return null;
		}
		String rest = m.group(1).trim();

		int qty = 1;
		Matcher q = QTY_PREFIX.matcher(rest);
		if (q.matches())
		{
			try
			{
				qty = Math.max(1, Integer.parseInt(q.group(1)));
			}
			catch (NumberFormatException ignored)
			{
			}
			rest = q.group(2).trim();
		}

		// cut at sentence/bracket/parenthesis boundaries, then at location phrasing.
		// note: split() can return an empty array when rest is only delimiters
		String[] parts = rest.split("[.\\[(,]");
		rest = parts.length > 0 ? parts[0].trim() : "";
		rest = GATHER_CUTOFF.matcher(rest).replaceAll("").trim();
		rest = rest.replaceAll("^(?:the|a|an)\\s+", "");

		if (rest.isEmpty() || rest.length() > 30 || NOT_ITEMS.contains(rest.toLowerCase(java.util.Locale.ROOT)))
		{
			return null;
		}
		List<ItemReq> out = new ArrayList<>();
		out.add(new ItemReq(rest, qty));
		return out;
	}

	/** Withdraw list if present, otherwise gather item, otherwise null. */
	public static List<ItemReq> parse(String text)
	{
		List<ItemReq> w = parseWithdraw(text);
		return w != null ? w : parseGather(text);
	}
}
