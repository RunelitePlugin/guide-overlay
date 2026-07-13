package com.hcimguide;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tolerant parser that converts the guide's raw wikitext into the
 * Guide/Episode/Bank/Step model. It intentionally ignores anything it does
 * not understand (templates on their own lines, embedded videos, tables)
 * so that unrelated wiki edits do not break the plugin.
 */
public class WikitextParser
{
	private static final Pattern HEADING = Pattern.compile("^(={2,6})\\s*(.*?)\\s*\\1\\s*$");
	private static final Pattern BULLET = Pattern.compile("^([*#:]+)\\s*(.*)$");
	private static final Pattern EPISODE_TITLE = Pattern.compile("^Episode\\s+(\\d{1,3})\\b.*", Pattern.CASE_INSENSITIVE);
	private static final Pattern BANK_TITLE = Pattern.compile("^Bank\\s+([0-9]+[A-Za-z]?)\\b.*", Pattern.CASE_INSENSITIVE);

	private static final Pattern WIKILINK_PIPED = Pattern.compile("\\[\\[[^\\[\\]|]*\\|([^\\[\\]]*)]]");
	private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\[\\]|]*)]]");
	private static final Pattern EXTLINK_LABELED = Pattern.compile("\\[(?:https?|ftp)://\\S*\\s+([^\\]]*)]");
	private static final Pattern EXTLINK = Pattern.compile("\\[(?:https?|ftp)://[^\\]\\s]*]");
	private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([^{}]*)}}");
	private static final Pattern HTML_TAG = Pattern.compile("<[^<>]+>");

	/**
	 * Parses in strict Episode/Bank mode first; when the page has no
	 * "Episode N" headings at all (an arbitrary wiki guide), falls back to a
	 * generic mode where every top-level heading becomes a chapter and every
	 * deeper heading becomes a section, so most bullet-list guide pages work.
	 */
	public Guide parse(String wikitext)
	{
		Guide strict = parseInternal(wikitext, false);
		if (!strict.isEmpty())
		{
			return strict;
		}
		return parseInternal(wikitext, true);
	}

	/** Hard cap on steps per guide - a runaway page can't OOM the client or freeze Swing. */
	private static final int MAX_STEPS = 25_000;

	private Guide parseInternal(String wikitext, boolean generic)
	{
		Guide guide = new Guide();
		if (wikitext == null)
		{
			return guide;
		}
		int totalSteps = 0;
		int genericChapter = 0;
		int chapterLevel = -1; // in generic mode: the first heading level seen becomes chapter level

		GuideEpisode episode = null;
		GuideBank bank = null;
		// occurrence counter of identical step text within a bank, for stable keys
		Map<String, Integer> occurrence = new HashMap<>();

		for (String rawLine : wikitext.split("\r?\n"))
		{
			String line = rawLine.trim();
			if (line.isEmpty())
			{
				continue;
			}

			Matcher h = HEADING.matcher(line);
			if (h.matches())
			{
				String title = cleanInline(h.group(2));
				int level = h.group(1).length();

				boolean isChapter;
				if (generic)
				{
					if (chapterLevel < 0)
					{
						chapterLevel = level;
					}
					isChapter = level <= chapterLevel;
				}
				else
				{
					isChapter = EPISODE_TITLE.matcher(title).matches();
				}

				if (isChapter)
				{
					int number;
					if (generic)
					{
						number = ++genericChapter;
					}
					else
					{
						Matcher ep = EPISODE_TITLE.matcher(title);
						//noinspection ResultOfMethodCallIgnored
						ep.matches();
						number = Integer.parseInt(ep.group(1));
					}
					episode = new GuideEpisode(number, title);
					guide.getEpisodes().add(episode);
					bank = null;
					continue;
				}

				if (episode != null)
				{
					Matcher bk = BANK_TITLE.matcher(title);
					String id;
					if (bk.matches())
					{
						// episode-namespaced so duplicate bank numbers across episodes can never collide
						id = "E" + episode.getNumber() + ".B" + bk.group(1).toUpperCase();
					}
					else
					{
						id = "E" + episode.getNumber() + "-" + slug(title);
					}
					bank = new GuideBank(id, title, episode.getNumber());
					episode.getBanks().add(bank);
					occurrence.clear();
				}
				else
				{
					// heading before the first episode (intro/terminology) - skipped
					bank = null;
				}
				continue;
			}

			Matcher b = BULLET.matcher(line);
			if (b.matches() && episode != null)
			{
				if (totalSteps >= MAX_STEPS)
				{
					continue;
				}
				String text = cleanInline(b.group(2));
				if (text.isEmpty())
				{
					continue;
				}
				totalSteps++;
				if (bank == null)
				{
					bank = new GuideBank("E" + episode.getNumber() + "-notes", "Notes", episode.getNumber());
					episode.getBanks().add(bank);
					occurrence.clear();
				}
				int depth = Math.max(0, b.group(1).length() - 1);
				String occKey = bank.getId() + " " + text;
				int occ = occurrence.merge(occKey, 1, Integer::sum) - 1;
				// String.hashCode is stable across JVMs. Identical texts in a bank are
				// separated by the occurrence suffix; a 32-bit collision between two
				// DIFFERENT texts within one ~30-step bank is vanishingly unlikely.
				String key = bank.getId() + "#" + Integer.toHexString(text.hashCode()) + "#" + occ;
				bank.getSteps().add(new GuideStep(key, text, depth, bank.getId()));
			}
			// anything else (plain paragraphs, templates, iframes) is ignored
		}

		return guide;
	}

	/**
	 * Strips wiki markup from a single line, leaving readable text.
	 */
	static String cleanInline(String s)
	{
		if (s == null)
		{
			return "";
		}
		String out = s;

		// templates may be nested; peel from the inside out a few times
		for (int i = 0; i < 5 && out.contains("{{"); i++)
		{
			out = replaceTemplates(out);
		}

		out = WIKILINK_PIPED.matcher(out).replaceAll("$1");

		Matcher m = WIKILINK.matcher(out);
		StringBuffer sb = new StringBuffer();
		while (m.find())
		{
			String target = m.group(1).replace('_', ' ');
			// drop namespace prefixes like "File:.." entirely
			if (target.regionMatches(true, 0, "File:", 0, 5) || target.regionMatches(true, 0, "Image:", 0, 6))
			{
				m.appendReplacement(sb, "");
			}
			else
			{
				m.appendReplacement(sb, Matcher.quoteReplacement(target));
			}
		}
		m.appendTail(sb);
		out = sb.toString();

		out = EXTLINK_LABELED.matcher(out).replaceAll("$1");
		out = EXTLINK.matcher(out).replaceAll("");
		out = out.replace("'''", "").replace("''", "");
		// SECURITY: decode entities BEFORE stripping tags, so entity-escaped
		// markup ("&lt;html&gt;") becomes a real tag and is removed with the
		// rest. "&amp;" is decoded LAST to prevent double-decoding
		// ("&amp;lt;" must yield the literal text "&lt;", not "<").
		out = out.replace("&nbsp;", " ")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&#39;", "'")
			.replace("&quot;", "\"")
			.replace("&amp;", "&");
		out = HTML_TAG.matcher(out).replaceAll("");
		// belt and braces: no angle bracket may survive into display strings -
		// Swing renders any label/tooltip text starting with "<html>" as HTML
		out = out.replace("<", "").replace(">", "");
		out = out.replaceAll("\\s+", " ").trim();
		return out;
	}

	private static String replaceTemplates(String s)
	{
		Matcher m = TEMPLATE.matcher(s);
		StringBuffer sb = new StringBuffer();
		while (m.find())
		{
			String inner = m.group(1);
			String replacement = "";
			// {{plink|Item name|...}} or {{SCP|Skill|level}} -> first useful positional arg
			String[] parts = inner.split("\\|");
			for (int i = 1; i < parts.length; i++)
			{
				String p = parts[i].trim();
				if (!p.isEmpty() && !p.contains("="))
				{
					replacement = p;
					break;
				}
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static String slug(String s)
	{
		return s.toLowerCase(java.util.Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
	}
}
