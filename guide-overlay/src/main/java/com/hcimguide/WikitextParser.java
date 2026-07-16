package com.hcimguide;

import java.util.HashMap;
import java.util.Locale;
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
	/**
	 * Strict standalone bank label. Unlike BANK_TITLE this deliberately does
	 * not accept ordinary prose such as "Bank 9 duplicates Bank 8". Optional
	 * location/context text must be separated from the number by punctuation.
	 */
	private static final Pattern BANK_LABEL = Pattern.compile(
		"^Bank\\s*#?\\s*([0-9]+[A-Za-z]?)(?:\\s*(?:[-:–—]\\s*.+|\\([^)]*\\)|\\[[^]]*]))?\\s*[.:]?\\s*$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern BOLD_RUN = Pattern.compile("'''\\s*(.*?)\\s*'''");
	// NOTE: HTML comments are stripped with the regex-free stripHtmlComments()
	// - a lazy <!--.*?--> pattern goes quadratic on unterminated prefixes

	/**
	 * The live guide's ONLY literal bank number: an HTML comment right before
	 * each {{Checklist}} block ("&lt;!-- Bank 12 --&gt;", "&lt;!-- Bank 105A --&gt;").
	 */
	private static final Pattern BANK_COMMENT = Pattern.compile(
		"^<!--\\s*Bank\\s*#?\\s*([0-9]+[A-Za-z]?)\\s*-->$", Pattern.CASE_INSENSITIVE);

	/** Extracts a {{Checklist}} block's title= argument (may be empty or computed). */
	private static String checklistTitle(String line)
	{
		int t = line.indexOf("title=");
		if (t < 0)
		{
			return "";
		}
		int start = t + "title=".length();
		int end = start;
		int brace = 0;
		while (end < line.length())
		{
			char c = line.charAt(end);
			if (c == '{')
			{
				brace++;
			}
			else if (c == '}')
			{
				brace--;
			}
			else if (c == '|' && brace == 0)
			{
				break;
			}
			end++;
		}
		String raw = line.substring(start, end);
		// computed titles ("Bank {{#expr:{{#var:bankNumber}}+1}}") carry no
		// usable number - treat as empty so the block continues the current
		// section (the preceding <!-- Bank N --> comment names real banks)
		if (raw.contains("{{"))
		{
			return "";
		}
		return cleanInline(raw).trim();
	}

	/**
	 * File/Image embeds, including piped captions and ONE level of nested
	 * links inside the caption. Removed entirely - before piped-link handling,
	 * which would otherwise keep the caption as text.
	 */
	private static final Pattern FILE_LINK = Pattern.compile(
		"(?i)\\[\\[(?:File|Image):[^\\[\\]]*(?:\\[\\[[^\\[\\]]*]][^\\[\\]]*)*]]");
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
		Map<String, GuideBank> banksById = new HashMap<>();
		// literal bank number announced by a "<!-- Bank N -->" comment, waiting
		// for its {{Checklist}} block (an image line may sit between them)
		String pendingBank = null;

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
					banksById.clear();
					pendingBank = null;
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
					bank = findOrCreateBank(episode, banksById, id, title);
				}
				else
				{
					// heading before the first episode (intro/terminology) - skipped
					bank = null;
				}
				continue;
			}

			// THE LIVE GUIDE'S REAL FORMAT (verified against the actual stored
			// snapshot): each bank is a {{Checklist|title=Bank {{#expr:...}}|
			// template whose number is COMPUTED by wiki parser functions - the
			// only literal number lives in an HTML comment ("<!-- Bank 12 -->")
			// placed just before the block. So: remember the comment's number,
			// then open that bank when its {{Checklist}} arrives. Checklists
			// with a literal title ("Herblore") become named sections; ones
			// with an empty or computed-but-unannounced title CONTINUE the
			// current section (the wiki uses those as visual continuation
			// boxes around screenshots).
			boolean handledAsChecklist = false;
			if (episode != null)
			{
				Matcher bc = BANK_COMMENT.matcher(line);
				if (bc.matches())
				{
					pendingBank = bc.group(1).toUpperCase(Locale.ROOT);
					continue;
				}
				if (line.startsWith("{{Checklist"))
				{
					if (pendingBank != null)
					{
						bank = findOrCreateBank(episode, banksById,
							"E" + episode.getNumber() + ".B" + pendingBank, "Bank " + pendingBank);
						pendingBank = null;
					}
					else
					{
						String title = checklistTitle(line);
						if (!title.isEmpty())
						{
							Matcher tb = BANK_LABEL.matcher(title);
							String id = tb.matches()
								? "E" + episode.getNumber() + ".B" + tb.group(1).toUpperCase(Locale.ROOT)
								: "E" + episode.getNumber() + "-" + slug(title);
							bank = findOrCreateBank(episode, banksById, id, title);
						}
						// empty/computed title: continuation of the current section
					}
					// a checklist's FIRST item can sit on the opening line
					// ("{{Checklist|title=|* If you didn't get your spade...")
					int inline = line.indexOf("|*");
					if (inline < 0)
					{
						continue;
					}
					line = line.substring(inline + 1);
					handledAsChecklist = true; // fall through to the bullet code
				}
			}

			// Other guides mark banks as visible text: bold lines, list items,
			// table cells, captions. Extract the label from the whole raw line;
			// the anchored matcher keeps prose ("Bank 9 duplicates Bank 8") as
			// prose.
			if (episode != null && !handledAsChecklist)
			{
				String bankLabel = extractBankLabel(rawLine);
				if (bankLabel != null)
				{
					Matcher bk = BANK_LABEL.matcher(bankLabel);
					//noinspection ResultOfMethodCallIgnored
					bk.matches();
					String id = "E" + episode.getNumber() + ".B" + bk.group(1).toUpperCase(Locale.ROOT);
					bank = findOrCreateBank(episode, banksById, id, bankLabel);
					continue;
				}
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
					String id = "E" + episode.getNumber() + "-notes";
					bank = findOrCreateBank(episode, banksById, id, "Notes");
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

	private static GuideBank findOrCreateBank(GuideEpisode episode, Map<String, GuideBank> banksById,
		String id, String title)
	{
		GuideBank existing = banksById.get(id);
		if (existing != null)
		{
			return existing;
		}
		GuideBank created = new GuideBank(id, title, episode.getNumber());
		banksById.put(id, created);
		episode.getBanks().add(created);
		return created;
	}

	/**
	 * Returns a normalized standalone bank label, or null when the line is
	 * ordinary guide prose. This intentionally tolerates wiki layout wrappers
	 * while keeping the semantic match strict.
	 */
	/**
	 * A real bank-label line (label + attached screenshot markup) is short.
	 * Lines beyond this can only be prose or hostile input, and the
	 * label-extraction work below must never run on megabyte lines - a
	 * crafted page could otherwise turn the per-candidate surrounding scan
	 * quadratic and hang guide parsing for hours.
	 */
	private static final int MAX_LABEL_LINE_CHARS = 4096;

	/** Bound the expensive surrounding-text checks per line (real lines have one). */
	private static final int MAX_LABEL_CANDIDATES = 4;

	static String extractBankLabel(String rawLine)
	{
		if (rawLine == null || rawLine.length() > MAX_LABEL_LINE_CHARS)
		{
			return null;
		}
		String raw = stripHtmlComments(rawLine).trim();
		if (raw.isEmpty())
		{
			return null;
		}

		// First prefer an explicitly bold bank label. Search the ORIGINAL line
		// so a caption such as [[File:bank.png|thumb|'''Bank 12''']] is not
		// discarded before we see it. For the surrounding-text check, remove
		// the complete image block with the balanced scanner; that still rejects
		// prose such as "Go to '''Bank 9''' after the quest".
		Matcher bold = BOLD_RUN.matcher(raw);
		int candidatesChecked = 0;
		while (bold.find() && candidatesChecked < MAX_LABEL_CANDIDATES)
		{
			String candidate = normalizeBankCandidate(cleanInline(bold.group(1)));
			if (!isBankLabel(candidate))
			{
				continue;
			}
			candidatesChecked++;
			String surrounding = raw.substring(0, bold.start()) + " " + raw.substring(bold.end());
			surrounding = stripFileLinks(surrounding);
			surrounding = normalizeLayoutJunk(cleanInline(surrounding));
			if (surrounding.isEmpty() || surrounding.matches("[-:–—].{0,60}"))
			{
				return candidate;
			}
		}

		String withoutFiles = stripFileLinks(raw);
		// Also support unbolded/table/list/HTML-wrapped standalone labels. The
		// exact anchored matcher is what prevents a normal instruction sentence
		// beginning with the word Bank from becoming a section accidentally.
		String remainder = normalizeBankCandidate(cleanInline(withoutFiles));
		if (isBankLabel(remainder))
		{
			return remainder;
		}

		// Caption-only bank label ([[File:bank.png|thumb|Bank 14]] alone on a
		// line): ONLY when nothing else remains on the line - a step that
		// merely carries a "Bank N"-captioned screenshot must stay a step,
		// not become a section marker that swallows the step and re-keys
		// everything after it.
		if (remainder.isEmpty())
		{
			return extractBankLabelFromFileCaptions(raw);
		}
		return null;
	}

	/**
	 * Regex-free HTML comment stripping: the naive {@code <!--.*?-->} pattern
	 * goes quadratic on a long line full of unterminated "<!--" prefixes.
	 * An unterminated comment discards the remainder of the line, matching
	 * MediaWiki's own behavior closely enough for label detection.
	 */
	static String stripHtmlComments(String s)
	{
		int start = s.indexOf("<!--");
		if (start < 0)
		{
			return s;
		}
		StringBuilder sb = new StringBuilder(s.length());
		int i = 0;
		while (start >= 0)
		{
			sb.append(s, i, start);
			int end = s.indexOf("-->", start + 4);
			if (end < 0)
			{
				return sb.toString(); // unterminated: drop the rest
			}
			sb.append(' ');
			i = end + 3;
			start = s.indexOf("<!--", i);
		}
		sb.append(s, i, s.length());
		return sb.toString();
	}

	/** Find an exact bank label in a top-level File/Image link caption field. */
	private static String extractBankLabelFromFileCaptions(String s)
	{
		int i = 0;
		while (i < s.length())
		{
			if (!startsFileLink(s, i))
			{
				i++;
				continue;
			}
			int end = endOfWikiLink(s, i);
			if (end < 0)
			{
				return null;
			}
			String body = s.substring(i + 2, end - 2);
			for (String field : splitTopLevelPipes(body))
			{
				String candidate = normalizeBankCandidate(cleanInline(field));
				if (isBankLabel(candidate))
				{
					return candidate;
				}
			}
			i = end;
		}
		return null;
	}

	/** Split File-link options without splitting pipes inside nested wiki links/templates. */
	private static java.util.List<String> splitTopLevelPipes(String s)
	{
		java.util.List<String> fields = new java.util.ArrayList<>();
		int squareDepth = 0;
		int braceDepth = 0;
		int start = 0;
		for (int i = 0; i < s.length(); i++)
		{
			if (i + 1 < s.length() && s.charAt(i) == '[' && s.charAt(i + 1) == '[')
			{
				squareDepth++;
				i++;
			}
			else if (i + 1 < s.length() && s.charAt(i) == ']' && s.charAt(i + 1) == ']')
			{
				squareDepth = Math.max(0, squareDepth - 1);
				i++;
			}
			else if (i + 1 < s.length() && s.charAt(i) == '{' && s.charAt(i + 1) == '{')
			{
				braceDepth++;
				i++;
			}
			else if (i + 1 < s.length() && s.charAt(i) == '}' && s.charAt(i + 1) == '}')
			{
				braceDepth = Math.max(0, braceDepth - 1);
				i++;
			}
			else if (s.charAt(i) == '|' && squareDepth == 0 && braceDepth == 0)
			{
				fields.add(s.substring(start, i));
				start = i + 1;
			}
		}
		fields.add(s.substring(start));
		return fields;
	}

	private static boolean isBankLabel(String candidate)
	{
		return candidate != null && candidate.length() <= 100 && BANK_LABEL.matcher(candidate).matches();
	}

	private static String normalizeBankCandidate(String s)
	{
		if (s == null)
		{
			return "";
		}
		return normalizeLayoutJunk(s)
			.replaceAll("\\s+", " ")
			.trim();
	}

	private static String normalizeLayoutJunk(String s)
	{
		String out = s == null ? "" : s.trim();
		// <gallery> entries use: File:name.png|caption
		out = out.replaceFirst("(?i)^(?:File|Image):[^|]+\\|\\s*", "");
		// MediaWiki table cell syntax: | style="..." | content
		out = out.replaceFirst("^[|!]\\s*[^|]*=\\s*[^|]*\\|\\s*", "");
		// table/list/definition prefixes and harmless trailing cell punctuation
		out = out.replaceFirst("^[|!;:*#\\s]+", "");
		out = out.replaceFirst("[|!;\\s]+$", "");
		return out.trim();
	}

	/** Remove complete [[File:...]] / [[Image:...]] blocks with nested links. */
	private static String stripFileLinks(String s)
	{
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length())
		{
			if (startsFileLink(s, i))
			{
				int end = endOfWikiLink(s, i);
				if (end < 0)
				{
					// malformed image markup: discard the remainder rather than
					// leaking an arbitrary caption into a section/step label
					break;
				}
				i = end;
				continue;
			}
			out.append(s.charAt(i++));
		}
		return out.toString();
	}

	private static boolean startsFileLink(String s, int at)
	{
		return regionMatchesIgnoreCase(s, at, "[[File:") || regionMatchesIgnoreCase(s, at, "[[Image:");
	}

	private static boolean regionMatchesIgnoreCase(String s, int at, String prefix)
	{
		return at >= 0 && at + prefix.length() <= s.length()
			&& s.regionMatches(true, at, prefix, 0, prefix.length());
	}

	/** Returns index immediately after the balanced closing brackets. */
	private static int endOfWikiLink(String s, int start)
	{
		int depth = 0;
		for (int i = start; i + 1 < s.length(); i++)
		{
			if (s.charAt(i) == '[' && s.charAt(i + 1) == '[')
			{
				depth++;
				i++;
			}
			else if (s.charAt(i) == ']' && s.charAt(i + 1) == ']')
			{
				depth--;
				i++;
				if (depth == 0)
				{
					return i + 1;
				}
			}
		}
		return -1;
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
		String out = stripFileLinks(s);

		// templates may be nested; peel from the inside out a few times
		for (int i = 0; i < 5 && out.contains("{{"); i++)
		{
			out = replaceTemplates(out);
		}

		// Regex fallback for malformed/simple image syntax not caught above.
		out = FILE_LINK.matcher(out).replaceAll("");

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

	/**
	 * HTML-safety for display strings from ANY source (used by the JSON guide
	 * parser too): strip tags and angle brackets so Swing can never render a
	 * label/tooltip as live HTML, and collapse whitespace. Does not touch wiki
	 * markup - JSON text has none.
	 */
	static String sanitizeDisplay(String s)
	{
		if (s == null)
		{
			return "";
		}
		String out = HTML_TAG.matcher(s).replaceAll("");
		out = out.replace("<", "").replace(">", "");
		return out.replaceAll("\\s+", " ").trim();
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
