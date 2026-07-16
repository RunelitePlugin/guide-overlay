package com.hcimguide;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single home for the name-normalization rules used to compare guide text
 * against NPC names, inventory item names and quest names.
 *
 * All patterns are precompiled: these methods run in per-tick code paths,
 * and {@code String.replaceAll} would compile a fresh Pattern on every call.
 * {@link Locale#ROOT} is used throughout so matching behaves identically on
 * every system locale.
 */
public final class Names
{
	private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");
	private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9]+");

	private Names()
	{
	}

	/** Lowercase (root locale) with everything but letters/digits removed. */
	public static String normalize(String s)
	{
		if (s == null)
		{
			return "";
		}
		return NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
	}

	/**
	 * Word-by-word plural stripping so "Jugs of wine" matches "Jug of wine".
	 * Both sides of any comparison get the same transformation, so
	 * over-stripping (e.g. "glass" -&gt; "glas") is harmless for equality.
	 */
	public static String singularize(String s)
	{
		if (s == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (String word : WORD_SPLIT.split(s.toLowerCase(Locale.ROOT)))
		{
			if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss"))
			{
				sb.append(word, 0, word.length() - 1);
			}
			else
			{
				sb.append(word);
			}
		}
		return sb.toString();
	}

	/**
	 * Loose match for NPC names that tolerates prefixes in either direction
	 * ("Veos" vs "Veos the sailor"). Both arguments must already be
	 * {@link #normalize(String) normalized}.
	 */
	public static boolean matchNormalized(String a, String b)
	{
		return !a.isEmpty() && !b.isEmpty()
			&& (a.equals(b) || b.startsWith(a) || a.startsWith(b));
	}
}
