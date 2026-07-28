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
			if (word.length() > 4 && word.endsWith("ies"))
			{
				// "berries" -> "berry"
				sb.append(word, 0, word.length() - 3).append('y');
			}
			else if (word.length() > 4 && word.endsWith("es")
				&& (word.endsWith("ches") || word.endsWith("shes")
					|| word.endsWith("sses") || word.endsWith("xes")
					|| word.endsWith("zzes")))
			{
				// "branches" -> "branch", "glasses" -> "glass", "boxes" -> "box".
				// Deliberately narrow: a blanket "-es" rule turned "pickaxes"
				// into "pickax", "roses" into "ros" and "shoes" into "sho",
				// because those stems already end in e.
				sb.append(word, 0, word.length() - 2);
			}
			else if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss"))
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

	/**
	 * Every plausible singular form of a name.
	 *
	 * <p>No single rule works: "boxes" needs two characters removed to reach
	 * "box", while "pickaxes" needs one to reach "pickaxe". Reducing to one
	 * guess corrupted "pickaxes" to "pickax", "roses" to "ros" and "shoes" to
	 * "sho". Producing candidates and matching if any of them agree avoids
	 * having to choose.</p>
	 *
	 * <p>Results are normalized, so they compare directly against
	 * {@link #normalize}d names.</p>
	 */
	public static java.util.Set<String> singularCandidates(String s)
	{
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		if (s == null || s.isEmpty())
		{
			return out;
		}
		out.add(normalize(s));
		out.add(singularize(s));

		String[] words = WORD_SPLIT.split(s.toLowerCase(java.util.Locale.ROOT));
		// each word contributes its own variants; combine positionally so a
		// multi-word name like "buckets of sand" still lines up with the item
		java.util.List<java.util.List<String>> perWord = new java.util.ArrayList<>();
		for (String w : words)
		{
			java.util.LinkedHashSet<String> forms = new java.util.LinkedHashSet<>();
			forms.add(w);
			if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss"))
			{
				forms.add(w.substring(0, w.length() - 1));
			}
			if (w.length() > 4 && w.endsWith("es"))
			{
				forms.add(w.substring(0, w.length() - 2));
			}
			if (w.length() > 4 && w.endsWith("ies"))
			{
				forms.add(w.substring(0, w.length() - 3) + "y");
			}
			perWord.add(new java.util.ArrayList<>(forms));
		}
		// bounded expansion: only the FIRST and LAST word are varied together,
		// which covers "buckets of sand" and "steel chainbodies" without a
		// combinatorial blowup on long names
		if (!perWord.isEmpty())
		{
			int last = perWord.size() - 1;
			for (String first : perWord.get(0))
			{
				for (String tail : perWord.get(last))
				{
					StringBuilder sb = new StringBuilder();
					for (int i = 0; i < perWord.size(); i++)
					{
						if (i == 0)
						{
							sb.append(first);
						}
						else if (i == last)
						{
							sb.append(tail);
						}
						else
						{
							sb.append(perWord.get(i).get(0));
						}
					}
					out.add(sb.toString());
				}
			}
		}
		out.remove("");
		return out;
	}

	/** True when two names share any singular form. */
	public static boolean singularMatch(String a, String b)
	{
		java.util.Set<String> ca = singularCandidates(a);
		for (String candidate : singularCandidates(b))
		{
			if (ca.contains(candidate))
			{
				return true;
			}
		}
		return false;
	}
}
