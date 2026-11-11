package com.hcimguide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
	/**
	 * Bold run. Deliberately NO whitespace quantifiers around the lazy group:
	 * three adjacent variable-width quantifiers backtrack cubically on a long
	 * run of spaces after an unclosed opener. The captured content is trimmed
	 * in code instead.
	 */
	private static final Pattern BOLD_RUN = Pattern.compile("'''(.*?)'''");
	/**
	 * {{Youtube|id}} embed template. The id must be the FIRST argument and be
	 * terminated by '|' or '}' - so named parameters ({{Youtube|width=400|id}})
	 * and over-long ids yield no video at all rather than a wrong URL, in
	 * keeping with the tolerant-parser design. Both live capitalizations of
	 * the template name match.
	 */
	private static final Pattern YOUTUBE_TEMPLATE = Pattern.compile("\\{\\{\\s*+[Yy]ou[Tt]ube\\s*+\\|\\s*+([A-Za-z0-9_-]{5,20})\\s*+[|}]");
	// NOTE: HTML comments are stripped with the regex-free stripHtmlComments()
	// - a lazy <!--.*?--> pattern goes quadratic on unterminated prefixes

	/**
	 * The live guide's ONLY literal bank number: an HTML comment right before
	 * each {{Checklist}} block ("&lt;!-- Bank 12 --&gt;", "&lt;!-- Bank 105A --&gt;").
	 */
	private static final Pattern BANK_COMMENT = Pattern.compile(
		"^<!--\\s*+Bank\\s*+#?\\s*+([0-9]++[A-Za-z]?)\\s*+-->$", Pattern.CASE_INSENSITIVE);

	/**
	 * Video URL in a standalone marker line: a {{Youtube|id}} embed or a
	 * bare/labeled link. The wiki uses {{Youtube}} in two roles - section
	 * videos AND inline embeds of the video the PREVIOUS step already links
	 * (bullet with the URL, then the embed). An embed whose id appears in the
	 * previous line is the step's video, already surfaced on the step row, so
	 * it must not be promoted to "section video guide".
	 */
	private static String bankVideoIn(String line, String previousLine)
	{
		Matcher yt = YOUTUBE_TEMPLATE.matcher(line);
		if (yt.find())
		{
			String id = yt.group(1);
			if (previousLine != null && previousLine.contains(id))
			{
				return null;
			}
			return "https://youtu.be/" + id;
		}
		return VideoLinks.firstVideoUrl(line);
	}

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
		if (raw.length() > MAX_TITLE_CHARS)
		{
			// the title is copied into the section id and from there into
			// every step key of the block - it must stay short
			raw = raw.substring(0, MAX_TITLE_CHARS);
		}
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
		"(?i)\\[\\[(?:File|Image):[^\\[\\]]*+(?:\\[\\[[^\\[\\]]*+]][^\\[\\]]*+)*+]]");
	// Every quantifier below is possessive: each is followed by a character its
	// class excludes, so nothing is lost, and a failed match cannot re-walk
	// the scanned run once per candidate start.
	private static final Pattern WIKILINK_PIPED = Pattern.compile("\\[\\[[^\\[\\]|]*+\\|([^\\[\\]]*+)]]");
	private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\[\\]|]*+)]]");
	private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([^{}]*+)}}");
	private static final Pattern HTML_TAG = Pattern.compile("<[^<>]++>");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	/**
	 * "->" written as an arrow between actions ("Ardy Cloak -> DKP"). Not the
	 * tail of an HTML comment ("-->"), which must stay intact for tag removal.
	 */
	private static final Pattern ARROW = Pattern.compile("(?<!-)->");
	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(?:([0-9]{1,7})|[xX]([0-9a-fA-F]{1,6}));");

	/**
	 * Parses in strict Episode/Bank mode first; when the page has no
	 * "Episode N" headings at all (an arbitrary wiki guide), falls back to a
	 * generic mode where every top-level heading becomes a chapter and every
	 * deeper heading becomes a section, so most bullet-list guide pages work.
	 */
	public Guide parse(String wikitext)
	{
		if (wikitext != null && !wikitext.isEmpty() && wikitext.charAt(0) == '\uFEFF')
		{
			// a byte-order mark (files saved by some editors) would otherwise
			// hide the first line's heading from the "==" test
			wikitext = wikitext.substring(1);
		}
		Guide strict = parseInternal(wikitext, false);
		if (!strict.isEmpty())
		{
			return strict;
		}
		return parseInternal(wikitext, true);
	}

	/** Hard cap on steps per guide - a runaway page can't OOM the client or freeze Swing. */
	private static final int MAX_STEPS = 25_000;
	/**
	 * Structural caps (the step cap alone doesn't bound the MODEL: a file of
	 * nothing but headings creates episodes/banks without ever adding a
	 * step). Real guides: 20 episodes, ~230 banks - these are pure safety
	 * margins, and hitting one marks the guide truncated.
	 */
	private static final int MAX_EPISODES = 500;
	private static final int MAX_BANKS_PER_EPISODE = 500;
	/**
	 * Longest bullet line accepted as a step. Real steps are under ~1k chars;
	 * a pathological megabyte line would otherwise flow through every
	 * cleanup regex and into item parsing. Oversized lines are SKIPPED (and
	 * the guide marked truncated), never shortened: a shortened text would
	 * mint a different step key and silently orphan saved progress if the
	 * source were ever fixed.
	 */
	private static final int MAX_STEP_SOURCE_CHARS = 4096;
	/**
	 * Longest section/episode title kept. Titles feed the section id, which is
	 * copied into every step key of the section, so an unbounded title
	 * multiplies by the step count. Real titles are under 60 characters.
	 */
	private static final int MAX_TITLE_CHARS = 200;
	/**
	 * Longest section id. Ids prefix every step key and the persistence layer
	 * rejects keys over 512 characters, so this also bounds key length:
	 * id + "#" + 8 hex digits + "#" + occurrence stays far below that limit.
	 * An id built from a 64-character title slug always fits unchanged.
	 */
	private static final int MAX_BANK_ID_CHARS = 72;
	/**
	 * The persistence layer never stored a key longer than this, so an earlier
	 * key beyond it has no saved progress and needs no migration entry.
	 */
	private static final int MAX_PERSISTED_KEY_CHARS = 512;
	/** Deepest bullet nesting kept; the wiki guides use at most two levels. */
	private static final int MAX_DEPTH = 6;

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
		// previous non-empty line, for telling a section {{Youtube}} embed
		// apart from an inline embed of the preceding step's own video
		String lastNonEmpty = null;
		// occurrence counter in the PREVIOUS key generation's text space, and
		// section ids as that generation minted them - together they rebuild
		// each step's earlier key so saved progress can follow a text change
		Map<String, Integer> legacyOccurrence = new HashMap<>();
		Map<String, String> legacyBankIds = new HashMap<>();
		// the same, in the still older key space where every step of an episode
		// sat in one "Notes" section (see ProgressKeyMigration.migrateLegacyNotes,
		// which rebuilds those keys from the CURRENT text and so cannot follow
		// a text change by itself)
		Map<String, Integer> legacyNotesOccurrence = new HashMap<>();
		// true between a {{Checklist}} opener and its closing "}}"
		boolean inChecklist = false;
		// {{Extimage}} screenshots waiting for the section they illustrate: the
		// wiki puts each one just BEFORE its {{Checklist}}, on either side of
		// the "<!-- Bank N -->" comment
		List<String> pendingImages = new ArrayList<>();

		for (String rawLine : wikitext.split("\r\n|\r|\n"))
		{
			String line = rawLine.trim();
			if (line.isEmpty())
			{
				continue;
			}
			String previousLine = lastNonEmpty;
			lastNonEmpty = line;

			// headings are recognised by counting "=" - a regex with a lazy
			// group between whitespace runs backtracks cubically on a padded line
			int level = headingLevel(line);
			if (level > 0)
			{
				inChecklist = false;
				// same source cap as step lines: cleanInline runs a dozen
				// linear passes, and headings were the one line kind fed to it
				// unbounded - a pathological multi-megabyte heading must not
				// buy CPU the step lines already refuse
				String headingSource = stripRegexSpace(line.substring(level, line.length() - level));
				if (headingSource.length() > MAX_STEP_SOURCE_CHARS)
				{
					headingSource = headingSource.substring(0, MAX_STEP_SOURCE_CHARS);
				}
				String title = capTitle(cleanInline(headingSource));

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
					if (guide.getEpisodes().size() >= MAX_EPISODES)
					{
						// heading spam: stop growing the model - later steps
						// keep pooling into the current episode
						guide.markTruncated();
						continue;
					}
					episode = new GuideEpisode(number, title);
					guide.getEpisodes().add(episode);
					bank = null;
					banksById.clear();
					legacyBankIds.clear();
					pendingBank = null;
					pendingImages.clear();
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
					bank = findOrCreateBank(guide, episode, banksById, legacyBankIds, id, title);
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
			// NO regex below ever sees a line longer than the step cap: the
			// patterns are linear on real lines, but a megabyte line would still
			// buy megabytes of matcher work per pattern
			boolean oversized = line.length() > MAX_STEP_SOURCE_CHARS;
			if (episode != null && !oversized)
			{
				String image = GuideImageLinks.fromExtimage(line);
				if (image != null)
				{
					if (pendingImages.size() < GuideImageLinks.MAX_PER_BANK && !pendingImages.contains(image))
					{
						pendingImages.add(image);
					}
					continue;
				}
			}
			if (episode != null)
			{
				Matcher bc = oversized ? null : BANK_COMMENT.matcher(line);
				if (bc != null && bc.matches())
				{
					pendingBank = bc.group(1).toUpperCase(Locale.ROOT);
					continue;
				}
				if (line.startsWith("{{Checklist"))
				{
					inChecklist = true;
					if (pendingBank != null)
					{
						bank = findOrCreateBank(guide, episode, banksById, legacyBankIds,
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
							bank = findOrCreateBank(guide, episode, banksById, legacyBankIds, id, title);
						}
						// empty/computed title: continuation of the current section
					}
					if (bank != null)
					{
						attachImages(bank, pendingImages);
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

			if (oversized)
			{
				// skipped, never shortened (see MAX_STEP_SOURCE_CHARS); only a
				// dropped list item makes the import incomplete
				if (episode != null && isBulletStart(line.charAt(0)))
				{
					guide.markTruncated();
				}
				continue;
			}
			if (line.equals("}}"))
			{
				inChecklist = false;
				continue;
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
					bank = findOrCreateBank(guide, episode, banksById, legacyBankIds, id, bankLabel);
					continue;
				}
			}

			Matcher b = BULLET.matcher(line);
			if (b.matches() && episode != null)
			{
				if (totalSteps >= MAX_STEPS)
				{
					guide.markTruncated();
					continue;
				}
				String source = b.group(2);
				// the text (and so the key) the previous parser generation gave
				// this line, computed from the untouched source
				String legacyText = cleanInline(source, true);
				if (closesBlock(source))
				{
					// "* last item}}": the block's closing braces share the
					// bullet's line - they are markup, not step text
					source = source.substring(0, source.length() - 2);
					inChecklist = false;
				}
				String text = cleanInline(source);
				String legacyKey = null;
				String legacyNotesKey = null;
				if (!legacyText.isEmpty())
				{
					String notesId = "E" + episode.getNumber() + "-notes";
					String sectionId = bank != null ? bank.getId() : notesId;
					String legacyBankId = legacyBankIds.getOrDefault(sectionId, sectionId);
					String legacyHash = "#" + Integer.toHexString(legacyText.hashCode()) + "#";
					int legacyOcc = legacyOccurrence.merge(legacyBankId + " " + legacyText, 1, Integer::sum) - 1;
					legacyKey = legacyBankId + legacyHash + legacyOcc;
					int notesOcc = legacyNotesOccurrence.merge(notesId + " " + legacyText, 1, Integer::sum) - 1;
					legacyNotesKey = notesId + legacyHash + notesOcc;
				}
				if (text.isEmpty())
				{
					continue;
				}
				totalSteps++;
				if (bank == null)
				{
					String id = "E" + episode.getNumber() + "-notes";
					bank = findOrCreateBank(guide, episode, banksById, legacyBankIds, id, "Notes");
				}
				int depth = Math.min(MAX_DEPTH, Math.max(0, b.group(1).length() - 1));
				String occKey = bank.getId() + " " + text;
				int occ = occurrence.merge(occKey, 1, Integer::sum) - 1;
				// String.hashCode is stable across JVMs. Identical texts in a bank are
				// separated by the occurrence suffix; a 32-bit collision between two
				// DIFFERENT texts within one ~30-step bank is vanishingly unlikely.
				String key = bank.getId() + "#" + Integer.toHexString(text.hashCode()) + "#" + occ;
				// Registered in the SPLIT map (one old key -> its one current
				// key): that is the map replayed for skipped steps as well as
				// completed ones, so both kinds of saved mark follow the step.
				if (legacyKey != null && !legacyKey.equals(key) && legacyKey.length() <= MAX_PERSISTED_KEY_CHARS)
				{
					guide.getLegacySplitKeys().put(legacyKey, java.util.Collections.singletonList(key));
				}
				if (legacyNotesKey != null && !legacyText.equals(text) && !legacyNotesKey.equals(key))
				{
					guide.getLegacySplitKeys().putIfAbsent(legacyNotesKey, java.util.Collections.singletonList(key));
				}
				// screenshots that preceded a checklist with no section of its
				// own belong to the section its first step just opened
				attachImages(bank, pendingImages);
				GuideStep guideStep = new GuideStep(key, text, depth, bank.getId());
				// video URLs become a per-step "watch in browser" action; text
				// (and therefore keys) untouched. The RAW line is the fallback:
				// cleanInline strips labeled extlinks ("[url watch this]" ->
				// "watch this"), so their URL only survives pre-clean
				String videoUrl = VideoLinks.firstVideoUrl(text);
				if (videoUrl == null)
				{
					videoUrl = VideoLinks.firstVideoUrl(b.group(2));
				}
				guideStep.setVideoUrl(videoUrl);
				bank.getSteps().add(guideStep);
				continue;
			}

			// standalone video markers - "Video Guide: <url>" lines and
			// {{Youtube|id}} embed templates - attach to the bank they follow;
			// one placed right under an episode heading (before any bank) is
			// that EPISODE's video guide. First marker wins at each level. A
			// marker sitting between a "<!-- Bank N -->" comment and its
			// {{Checklist}} belongs to the ANNOUNCED bank, which doesn't exist
			// yet - never credit it to the previous bank.
			if (episode != null && !handledAsChecklist && pendingBank == null)
			{
				String video = bankVideoIn(line, previousLine);
				if (video != null)
				{
					if (bank != null)
					{
						if (bank.getVideoUrl() == null)
						{
							bank.setVideoUrl(video);
						}
					}
					else if (episode.getVideoUrl() == null)
					{
						episode.setVideoUrl(video);
					}
					continue;
				}
			}

			// A plain-text line INSIDE a checklist block is part of the list the
			// wiki renders ("IF 85 Crafting" above the steps it governs). It
			// becomes a note row. Its key carries an "n" occurrence marker: a
			// separate key space, so a note can never take over the key (and the
			// saved tick) of a real step with the same text.
			if (inChecklist && bank != null && !handledAsChecklist
				&& Character.isLetterOrDigit(line.charAt(0)))
			{
				String source = line;
				if (closesBlock(source))
				{
					source = source.substring(0, source.length() - 2);
					inChecklist = false;
				}
				String text = cleanInline(source);
				if (text.isEmpty())
				{
					continue;
				}
				if (totalSteps >= MAX_STEPS)
				{
					guide.markTruncated();
					continue;
				}
				totalSteps++;
				int occ = occurrence.merge(bank.getId() + " note " + text, 1, Integer::sum) - 1;
				String key = bank.getId() + "#" + Integer.toHexString(text.hashCode()) + "#n" + occ;
				bank.getSteps().add(new GuideStep(key, text, 0, bank.getId()));
				continue;
			}
			// anything else (plain paragraphs, templates, iframes) is ignored
		}

		return guide;
	}

	private static void attachImages(GuideBank bank, List<String> pending)
	{
		for (String image : pending)
		{
			bank.addImageUrl(image);
		}
		pending.clear();
	}

	private static GuideBank findOrCreateBank(Guide guide, GuideEpisode episode,
		Map<String, GuideBank> banksById, Map<String, String> legacyBankIds, String rawId, String title)
	{
		String id = boundId(rawId);
		if (!id.equals(rawId))
		{
			// the unbounded id an earlier generation minted for this section,
			// for rebuilding the step keys saved progress may be stored under
			legacyBankIds.putIfAbsent(id, rawId);
		}
		GuideBank existing = banksById.get(id);
		if (existing != null)
		{
			return existing;
		}
		if (banksById.size() >= MAX_BANKS_PER_EPISODE && !episode.getBanks().isEmpty())
		{
			// bank-marker spam: stop growing the model - further steps pool
			// into the episode's last bank instead of new structures
			guide.markTruncated();
			return episode.getBanks().get(episode.getBanks().size() - 1);
		}
		GuideBank created = new GuideBank(id, capTitle(title), episode.getNumber());
		banksById.put(id, created);
		episode.getBanks().add(created);
		return created;
	}

	/**
	 * Heading level (2-6) of a trimmed line, or 0 when it is not a heading.
	 * MediaWiki semantics: the level is the shorter of the leading and trailing
	 * "=" runs, capped at six; surplus "=" stay in the title.
	 */
	static int headingLevel(String line)
	{
		int n = line.length();
		if (n < 4 || line.charAt(0) != '=' || line.charAt(n - 1) != '=')
		{
			return 0;
		}
		int lead = 0;
		while (lead < n && line.charAt(lead) == '=')
		{
			lead++;
		}
		if (lead == n)
		{
			// nothing but "=": the two runs share the line
			return Math.min(6, n / 2);
		}
		int trail = 0;
		while (line.charAt(n - 1 - trail) == '=')
		{
			trail++;
		}
		int level = Math.min(6, Math.min(lead, trail));
		return level >= 2 ? level : 0;
	}

	/** Trims the characters a regex "\\s" matches (not every control character, as trim() would). */
	private static String stripRegexSpace(String s)
	{
		int start = 0;
		int end = s.length();
		while (start < end && isRegexSpace(s.charAt(start)))
		{
			start++;
		}
		while (end > start && isRegexSpace(s.charAt(end - 1)))
		{
			end--;
		}
		return s.substring(start, end);
	}

	private static boolean isRegexSpace(char c)
	{
		return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
	}

	private static boolean isBulletStart(char c)
	{
		return c == '*' || c == '#' || c == ':';
	}

	private static String capTitle(String title)
	{
		return title.length() > MAX_TITLE_CHARS ? title.substring(0, MAX_TITLE_CHARS - 1) + "…" : title;
	}

	/**
	 * Bounds a section id. The hash of the full id keeps two long ids that
	 * share a prefix apart instead of merging their sections.
	 */
	private static String boundId(String id)
	{
		if (id.length() <= MAX_BANK_ID_CHARS)
		{
			return id;
		}
		return id.substring(0, MAX_BANK_ID_CHARS - 9) + "-" + Integer.toHexString(id.hashCode());
	}

	/**
	 * True when a line ends with the "}}" that closes its enclosing template
	 * block rather than a template of its own ("* text {{plink|X}}").
	 */
	private static boolean closesBlock(String source)
	{
		if (!source.endsWith("}}"))
		{
			return false;
		}
		int balance = 0;
		for (int i = 0; i + 1 < source.length(); i++)
		{
			if (source.charAt(i) == '{' && source.charAt(i + 1) == '{')
			{
				balance++;
				i++;
			}
			else if (source.charAt(i) == '}' && source.charAt(i + 1) == '}')
			{
				balance--;
				i++;
			}
		}
		return balance < 0;
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
			String candidate = normalizeBankCandidate(cleanInline(stripRegexSpace(bold.group(1))));
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
		// Scanned by hand: as regexes these four rules put overlapping
		// character classes side by side, which is quadratic on a line of
		// repeated "=" or "|".
		String out = s == null ? "" : s.trim();
		int start = 0;
		int end = out.length();
		// <gallery> entries use: File:name.png|caption
		int prefix = regionMatchesIgnoreCase(out, 0, "File:") ? 5
			: regionMatchesIgnoreCase(out, 0, "Image:") ? 6 : -1;
		if (prefix > 0)
		{
			int pipe = out.indexOf('|', prefix);
			if (pipe > prefix)
			{
				start = skipRegexSpace(out, pipe + 1);
			}
		}
		// MediaWiki table cell syntax: | style="..." | content
		if (start < end && (out.charAt(start) == '|' || out.charAt(start) == '!'))
		{
			int pipe = out.indexOf('|', start + 1);
			int equals = out.indexOf('=', start + 1);
			if (pipe > 0 && equals > 0 && equals < pipe)
			{
				start = skipRegexSpace(out, pipe + 1);
			}
		}
		// table/list/definition prefixes and harmless trailing cell punctuation
		while (start < end && (isRegexSpace(out.charAt(start)) || "|!;:*#".indexOf(out.charAt(start)) >= 0))
		{
			start++;
		}
		while (end > start && (isRegexSpace(out.charAt(end - 1)) || "|!;".indexOf(out.charAt(end - 1)) >= 0))
		{
			end--;
		}
		return out.substring(start, end).trim();
	}

	private static int skipRegexSpace(String s, int from)
	{
		int i = from;
		while (i < s.length() && isRegexSpace(s.charAt(i)))
		{
			i++;
		}
		return i;
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
		return cleanInline(s, false);
	}

	/**
	 * @param legacy reproduce, character for character, the text the previous
	 *               parser generation produced (arrows flattened to "-",
	 *               fewer entities, control characters kept). Step keys hash
	 *               the text, so this is how a step's earlier key is rebuilt
	 *               for progress migration - never use it for display.
	 */
	static String cleanInline(String s, boolean legacy)
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

		out = stripExternalLinks(out, true);
		out = stripExternalLinks(out, false);
		out = out.replace("'''", "").replace("''", "");
		// SECURITY: decode entities BEFORE stripping tags, so entity-escaped
		// markup ("&lt;html&gt;") becomes a real tag and is removed with the
		// rest. "&amp;" is decoded LAST to prevent double-decoding
		// ("&amp;lt;" must yield the literal text "&lt;", not "<").
		if (!legacy)
		{
			out = decodeEntities(out);
		}
		out = out.replace("&nbsp;", " ")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&#39;", "'")
			.replace("&quot;", "\"")
			.replace("&amp;", "&");
		return stripMarkupCharacters(out, legacy);
	}

	/**
	 * External links, scanned by hand so a row of unclosed "[http://" stays
	 * linear (as a regex, every opener re-walks the rest of the line).
	 * Labeled: "[url label]" becomes "label" - the url is the whole run of
	 * non-whitespace after the scheme. Unlabeled: "[url]" is removed.
	 */
	private static String stripExternalLinks(String s, boolean labeled)
	{
		int at = nextExternalLink(s, 0);
		if (at < 0)
		{
			return s;
		}
		StringBuilder out = new StringBuilder(s.length());
		int copied = 0;
		while (at >= 0)
		{
			int end = at;
			if (labeled)
			{
				while (end < s.length() && !isRegexSpace(s.charAt(end)))
				{
					end++;
				}
				int label = skipRegexSpace(s, end);
				int close = label > end ? s.indexOf(']', label) : -1;
				if (close >= 0)
				{
					out.append(s, copied, at).append(s, label, close);
					copied = close + 1;
					end = close + 1;
				}
				else if (label > end)
				{
					// whitespace but no "]" anywhere after it: nothing further
					// along can close a label either
					break;
				}
				// else: the url run is not followed by whitespace; every
				// opener inside that run ends at the same place, so skip it
			}
			else
			{
				while (end < s.length() && s.charAt(end) != ']' && !isRegexSpace(s.charAt(end)))
				{
					end++;
				}
				if (end < s.length() && s.charAt(end) == ']')
				{
					out.append(s, copied, at);
					copied = end + 1;
					end++;
				}
				// else: an opener inside the scanned run stops at the same
				// whitespace, so the whole run is skipped
			}
			at = nextExternalLink(s, Math.max(end, at + 1));
		}
		return out.append(s, copied, s.length()).toString();
	}

	/** Index of the next "[http://", "[https://" or "[ftp://" at or after {@code from}, or -1. */
	private static int nextExternalLink(String s, int from)
	{
		for (int i = s.indexOf('[', from); i >= 0; i = s.indexOf('[', i + 1))
		{
			if (s.startsWith("http://", i + 1) || s.startsWith("https://", i + 1) || s.startsWith("ftp://", i + 1))
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Shared tail of every display-string cleaner: remove tags and angle
	 * brackets, collapse whitespace. Outside legacy mode the arrows guides
	 * write between actions survive as "→" ("Ardy Cloak -> DKP",
	 * "Vial > Tarromin > Limpwurt") instead of losing their ">" - so the
	 * no-angle-bracket rule below holds and the instruction stays readable.
	 */
	private static String stripMarkupCharacters(String s, boolean legacy)
	{
		String out = s;
		if (!legacy)
		{
			// BEFORE tag removal: a stray "<" earlier on the line would
			// otherwise pair with the arrow's ">" and delete the text between
			out = ARROW.matcher(out).replaceAll("→");
		}
		out = HTML_TAG.matcher(out).replaceAll("");
		if (!legacy)
		{
			// AFTER tag removal, so a real tag written "<b >" is already gone
			out = WHITESPACE.matcher(out).replaceAll(" ").replace(" > ", " → ");
		}
		// belt and braces: no angle bracket may survive into display strings -
		// Swing renders any label/tooltip text starting with "<html>" as HTML
		out = out.replace("<", "").replace(">", "");
		if (!legacy)
		{
			out = stripInvisible(out);
		}
		return WHITESPACE.matcher(out).replaceAll(" ").trim();
	}

	/**
	 * Drops characters that render as nothing or reorder their neighbours:
	 * control characters, zero-width marks and bidirectional overrides (a
	 * right-to-left override would display a step back to front).
	 * Whitespace controls are left for the caller's whitespace collapse.
	 */
	private static String stripInvisible(String s)
	{
		StringBuilder sb = null;
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			boolean drop = (c < 0x20 && !isRegexSpace(c)) || (c >= 0x7F && c <= 0x9F) || c == 0x00AD
				|| (c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E)
				|| (c >= 0x2060 && c <= 0x2064) || (c >= 0x2066 && c <= 0x2069)
				|| c == 0xFEFF;
			// no-break space and the Unicode line/paragraph separators
			boolean space = c == 0x00A0 || c == 0x2028 || c == 0x2029;
			if (!drop && !space)
			{
				if (sb != null)
				{
					sb.append(c);
				}
				continue;
			}
			if (sb == null)
			{
				sb = new StringBuilder(s.length()).append(s, 0, i);
			}
			if (space)
			{
				sb.append(' ');
			}
		}
		return sb == null ? s : sb.toString();
	}

	/**
	 * Numeric references and the handful of named entities wiki editors
	 * actually type. Runs before the caller's "&amp;" step, so an escaped
	 * reference ("&amp;rarr;") stays literal text. An unknown or out-of-range
	 * reference is left exactly as written.
	 */
	private static String decodeEntities(String s)
	{
		if (s.indexOf('&') < 0)
		{
			return s;
		}
		String out = s.replace("&ndash;", "–").replace("&mdash;", "—")
			.replace("&rarr;", "→").replace("&larr;", "←")
			.replace("&times;", "×").replace("&hellip;", "…")
			.replace("&frac12;", "½").replace("&apos;", "'")
			.replace("&lsquo;", "‘").replace("&rsquo;", "’")
			.replace("&ldquo;", "“").replace("&rdquo;", "”");
		if (out.indexOf("&#") < 0)
		{
			return out;
		}
		Matcher m = NUMERIC_ENTITY.matcher(out);
		StringBuffer sb = new StringBuffer();
		while (m.find())
		{
			int codePoint;
			try
			{
				codePoint = m.group(1) != null ? Integer.parseInt(m.group(1)) : Integer.parseInt(m.group(2), 16);
			}
			catch (NumberFormatException e)
			{
				codePoint = -1;
			}
			boolean valid = codePoint > 0 && codePoint <= Character.MAX_CODE_POINT
				&& !(codePoint >= 0xD800 && codePoint <= 0xDFFF);
			m.appendReplacement(sb, Matcher.quoteReplacement(
				valid ? new String(Character.toChars(codePoint)) : m.group()));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/**
	 * HTML-safety for display strings from ANY source (used by the JSON guide
	 * parser too): strip tags and angle brackets so Swing can never render a
	 * label/tooltip as live HTML, and collapse whitespace. Does not touch wiki
	 * markup - JSON text has none.
	 */
	static String sanitizeDisplay(String s)
	{
		return sanitizeDisplay(s, false);
	}

	/** @param legacy see {@link #cleanInline(String, boolean)} */
	static String sanitizeDisplay(String s, boolean legacy)
	{
		if (s == null)
		{
			return "";
		}
		return stripMarkupCharacters(s, legacy);
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
