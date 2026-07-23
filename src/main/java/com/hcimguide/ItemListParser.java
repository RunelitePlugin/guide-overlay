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

	// every expression below runs once per step during import - precompiled
	// so a large guide doesn't compile thousands of throwaway Patterns
	private static final Pattern SLOTS_PAREN = Pattern.compile("(?i)\\s*\\([^)]*slots?[^)]*\\)");
	private static final Pattern SLOTS_BRACKET = Pattern.compile("(?i)\\s*\\[[^\\]]*slots?[^\\]]*]");
	private static final Pattern SLOTS_UNCLOSED = Pattern.compile("(?i)\\s*\\([^)]*slots?.*$");
	private static final Pattern TRAILING_DOTS = Pattern.compile("[.\\s]+$");
	private static final Pattern LIST_SPLIT = Pattern.compile(",|&|\\+");
	private static final Pattern INSTRUCTION_FRAGMENT = Pattern.compile("(?i)^(?:wear|wield|equip|use|then|and|if)\\b.*");
	private static final Pattern GATHER_BOUNDARY = Pattern.compile("[.\\[(,&+]");
	private static final Pattern LEADING_ARTICLE = Pattern.compile("^(?:the|a|an)\\s+");

	private static final Set<String> NOT_ITEMS = new HashSet<>(Arrays.asList(
		"everything", "all", "it", "them", "both", "one", "this", "these"));

	private ItemListParser()
	{
	}

	/**
	 * Drops ONE trailing "(advice here)" parenthetical - but NEVER a short
	 * dose/charge suffix like "Prayer potion(4)", "Ring of dueling(8)" or
	 * "(t)", whose parenthetical is part of the real item name. Advice
	 * contains whitespace or is 8+ characters; suffixes are short and solid.
	 *
	 * A single left-to-right scan replacing the old
	 * {@code \s*\((?:[^)]*\s[^)]*|[^)]{8,})\)\s*$} regex, whose overlapping
	 * unbounded quantifiers backtracked quadratically on malformed
	 * whitespace-heavy text with an unclosed parenthesis (measured ~0.5s at
	 * 10k chars - enough to stall a guide import). Same semantics: the
	 * stripped region must end the string, its content can't contain a
	 * closing parenthesis, and like the regex's leftmost match it starts at
	 * the EARLIEST open parenthesis after the second-to-last close - so an
	 * unbalanced "(see (note)" strips whole, never leaving a dangling "(see".
	 */
	static String stripTrailingAdvice(String value)
	{
		int end = value.length();
		while (end > 0 && Character.isWhitespace(value.charAt(end - 1)))
		{
			end--;
		}
		if (end == 0 || value.charAt(end - 1) != ')')
		{
			return value;
		}
		int lastClose = value.lastIndexOf(')', end - 2);
		int open = value.indexOf('(', lastClose + 1);
		if (open < 0 || open >= end - 1)
		{
			return value;
		}
		String inner = value.substring(open + 1, end - 1);
		boolean advice = inner.length() >= 8;
		for (int i = 0; !advice && i < inner.length(); i++)
		{
			advice = Character.isWhitespace(inner.charAt(i));
		}
		if (!advice)
		{
			return value;
		}
		// also consume the whitespace that preceded the parenthetical
		while (open > 0 && Character.isWhitespace(value.charAt(open - 1)))
		{
			open--;
		}
		return value.substring(0, open);
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
		// drop "(8 Inventory slots)" style notes ANYWHERE in the list -
		// the live guide often continues after them ("... (14 Inventory
		// Slots) + Food"), so trailing-only stripping isn't enough; the third
		// covers a writer who forgot the closing parenthesis
		String list = m.group(1);
		list = SLOTS_PAREN.matcher(list).replaceAll("");
		list = SLOTS_BRACKET.matcher(list).replaceAll("");
		list = SLOTS_UNCLOSED.matcher(list).replaceAll("");
		list = stripTrailingAdvice(list);
		list = TRAILING_DOTS.matcher(list).replaceAll("");

		return parseList(list);
	}

	/**
	 * Items list from a JSON guide's " (Items: 100 gp, knife, logs)" step
	 * suffix (appended from its items_needed metadata), or null when the step
	 * has none. Display/bank-tag/icon use ONLY - unlike a Withdraw step this
	 * never becomes an auto-completion condition, because holding the items is
	 * not what completes such a step.
	 */
	public static List<ItemReq> parseItemsSuffix(String text)
	{
		if (text == null)
		{
			return null;
		}
		int idx = text.lastIndexOf("(Items: ");
		if (idx < 0)
		{
			return null;
		}
		String inner = text.substring(idx + "(Items: ".length()).trim();
		// the step may have been length-capped mid-suffix ("...…"): parse the
		// items that survived instead of dropping the whole list
		boolean truncated = false;
		if (inner.endsWith("…"))
		{
			inner = inner.substring(0, inner.length() - 1);
			truncated = true;
		}
		if (inner.endsWith(")"))
		{
			inner = inner.substring(0, inner.length() - 1);
			truncated = false; // the list closed before the cap hit
		}
		List<ItemReq> out = parseList(inner);
		if (truncated && out != null && !out.isEmpty())
		{
			// the cap cut mid-list: the final fragment ("kni…") is not a real
			// item name - drop it rather than show a bogus grid entry
			out.remove(out.size() - 1);
			if (out.isEmpty())
			{
				return null;
			}
		}
		return out;
	}

	/** Split a comma/&amp;/+-separated item list into requirements. */
	private static List<ItemReq> parseList(String list)
	{
		List<ItemReq> out = new ArrayList<>();
		// the guide separates items with commas, ampersands, and plus signs
		for (String part : LIST_SPLIT.split(list))
		{
			String p = stripTrailingAdvice(part.trim());
			// trailing sentences ("Ball of Wool. If 85 Crafting...")
			int sentence = p.indexOf(". ");
			if (sentence > 0)
			{
				p = p.substring(0, sentence);
			}
			p = p.trim();
			if (p.isEmpty())
			{
				continue;
			}
			// instruction fragments after '&' splits ("wear them", "equip it")
			if (INSTRUCTION_FRAGMENT.matcher(p).matches())
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

		// cut at sentence/bracket/parenthesis/conjunction boundaries, then at
		// location phrasing. split() can return an empty array when rest is
		// only delimiters
		String[] parts = GATHER_BOUNDARY.split(rest);
		rest = parts.length > 0 ? parts[0].trim() : "";
		rest = GATHER_CUTOFF.matcher(rest).replaceAll("").trim();
		rest = LEADING_ARTICLE.matcher(rest).replaceFirst("");

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
