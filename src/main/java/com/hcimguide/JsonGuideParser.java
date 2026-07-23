package com.hcimguide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parses structured-JSON guides (chapters -&gt; sections -&gt; steps) into the
 * same Guide/Episode/Bank/Step model as the wikitext parser, so every
 * downstream feature (checkboxes, highlighting, auto-completion, bank tags)
 * works identically regardless of the guide's original source.
 *
 * Supported shape (BRUHsailer's data/guide_data.json and anything matching):
 * <pre>
 * { "title": "...", "chapters": [
 *     { "title": "Chapter 1: ...", "sections": [
 *         { "title": "1.1: ...", "steps": [
 *             { "content": [{"text": "..."}], "nestedContent": [...],
 *               "metadata": {"items_needed": "...", "total_time": "..."} } ] } ] } ] }
 * </pre>
 *
 * Everything is defensive: missing keys, wrong types, and empty text degrade
 * to a smaller guide rather than throwing, and the same MAX_STEPS cap as the
 * wikitext parser bounds a hostile file.
 */
public class JsonGuideParser
{
	private static final int MAX_STEPS = 25_000;
	/**
	 * Structural caps, mirroring WikitextParser: the step cap alone doesn't
	 * bound the MODEL - a 16 MB file of empty chapters/sections would still
	 * allocate thousands of episodes and banks. Hitting one marks the guide
	 * truncated. Real BRUHsailer data: 4 chapters, ~30 sections.
	 */
	private static final int MAX_EPISODES = 500;
	private static final int MAX_SECTIONS_PER_CHAPTER = 500;
	private static final int MAX_TEXT = 400;
	/** Fragments shorter than this fold into their neighbor instead of standing alone. */
	private static final int MIN_FRAGMENT = 12;
	/** Sentences longer than this get a secondary split at comma boundaries. */
	private static final int LONG_FRAGMENT = 300;
	/** Target length for comma-split chunks of an over-long sentence. */
	private static final int CHUNK_TARGET = 150;
	/** A parenthetical protects against splitting only if it closes within this many chars. */
	private static final int PAREN_WINDOW = 160;
	/**
	 * Hard cap on the paragraph length fed to the splitter, against quadratic
	 * CPU on hostile imports (the short-fragment merge fold and the chunk
	 * fallback both re-copy within one paragraph; unbounded input made them
	 * O(n^2) - same class of guard as WikitextParser's MAX_LABEL caps, keep
	 * it). Real paragraphs top out ~2,200 chars; old-generation keys only
	 * ever read the first MAX_TEXT chars, so migration is unaffected.
	 */
	private static final int MAX_PARAGRAPH = 10_000;
	/** Dot-terminated tokens that do NOT end a sentence ("see e.g. Getting Rats"). */
	private static final java.util.Set<String> ABBREVIATIONS = new java.util.HashSet<>(java.util.Arrays.asList(
		"e.g", "i.e", "vs", "approx", "etc", "mr", "mrs", "st", "no"));

	/**
	 * First non-whitespace character is '{' - the cheap sniff shared by
	 * every JSON entry point, deliberately NOT trim(): trimming a 16 MB
	 * import allocates a second 16 MB string just to look at one char.
	 */
	private static boolean sniffJson(String text)
	{
		if (text == null)
		{
			return false;
		}
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (!Character.isWhitespace(c))
			{
				return c == '{';
			}
		}
		return false;
	}

	/** True when the text is a JSON guide this parser understands. */
	public static boolean looksLikeJsonGuide(String text)
	{
		if (!sniffJson(text))
		{
			return false;
		}
		try
		{
			// instance API, not JsonParser.parseString: the client bundles an
			// older gson where the static method does not exist
			JsonElement root = new JsonParser().parse(text);
			return root.isJsonObject() && root.getAsJsonObject().has("chapters");
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/**
	 * Parses the text as a JSON guide in ONE pass, or returns null when it
	 * isn't one (not JSON, not an object, or no "chapters") so the caller
	 * can fall back to the wikitext parser - without the parse-twice cost of
	 * calling {@link #looksLikeJsonGuide} first on a multi-MB import.
	 */
	public Guide tryParse(String text)
	{
		if (!sniffJson(text))
		{
			return null;
		}
		JsonObject root;
		try
		{
			JsonElement parsed = new JsonParser().parse(text);
			if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("chapters"))
			{
				return null;
			}
			root = parsed.getAsJsonObject();
		}
		catch (Exception e)
		{
			return null;
		}
		return build(root);
	}

	public Guide parse(String text)
	{
		if (text == null)
		{
			return new Guide();
		}
		JsonObject root;
		try
		{
			JsonElement parsed = new JsonParser().parse(text);
			if (!parsed.isJsonObject())
			{
				return new Guide();
			}
			root = parsed.getAsJsonObject();
		}
		catch (Exception e)
		{
			return new Guide();
		}
		return build(root);
	}

	private Guide build(JsonObject root)
	{
		Guide guide = new Guide();

		JsonArray chapters = optArray(root, "chapters");
		if (chapters == null)
		{
			return guide;
		}

		int stepCount = 0;
		int chapterNo = 0;
		for (JsonElement chapterEl : chapters)
		{
			if (!chapterEl.isJsonObject())
			{
				continue;
			}
			JsonObject chapter = chapterEl.getAsJsonObject();
			chapterNo++;
			if (guide.getEpisodes().size() >= MAX_EPISODES)
			{
				guide.markTruncated();
				continue;
			}
			GuideEpisode episode = new GuideEpisode(chapterNo, WikitextParser.sanitizeDisplay(optString(chapter, "title", "Chapter " + chapterNo)));

			// a chapter may hold sections, or steps directly. Banks and the
			// episode itself attach to the model only once populated, so a
			// malformed/hostile file of empty structures allocates nothing -
			// while chapterNo/sectionNo still count EVERY source structure, so
			// the C<n>.S<n> ids in persisted step keys never renumber when an
			// empty chapter sits between populated ones.
			JsonArray sections = optArray(chapter, "sections");
			if (sections == null)
			{
				JsonArray directSteps = optArray(chapter, "steps");
				if (directSteps != null)
				{
					GuideBank bank = new GuideBank("C" + chapterNo + ".S0", "Steps", chapterNo);
					stepCount = addSteps(guide, bank, directSteps, stepCount);
					if (!bank.getSteps().isEmpty())
					{
						episode.getBanks().add(bank);
					}
				}
			}
			else
			{
				int sectionNo = 0;
				for (JsonElement sectionEl : sections)
				{
					if (!sectionEl.isJsonObject())
					{
						continue;
					}
					JsonObject section = sectionEl.getAsJsonObject();
					sectionNo++;
					if (sectionNo > MAX_SECTIONS_PER_CHAPTER)
					{
						guide.markTruncated();
						continue;
					}
					String title = WikitextParser.sanitizeDisplay(optString(section, "title", "Section " + sectionNo));
					GuideBank bank = new GuideBank("C" + chapterNo + ".S" + sectionNo, title, chapterNo);
					stepCount = addSteps(guide, bank, optArray(section, "steps"), stepCount);
					if (!bank.getSteps().isEmpty())
					{
						episode.getBanks().add(bank);
					}
				}
			}
			if (!episode.getBanks().isEmpty())
			{
				guide.getEpisodes().add(episode);
			}
		}
		return guide;
	}

	private int addSteps(Guide guide, GuideBank bank, JsonArray steps, int stepCount)
	{
		if (steps == null)
		{
			return stepCount;
		}
		// occurrence indexes count IDENTICAL texts only (same contract as
		// WikitextParser): keys stay stable when unrelated steps are inserted
		// or removed upstream, so a re-import never resets unrelated progress.
		// Three key spaces are tracked separately so each generation of old
		// keys is reproduced exactly as its parser built them:
		//   occByText   - the CURRENT (sentence-split) step texts
		//   occByOld    - the pre-split verbatim paragraph texts
		//   occByLegacy - the space-joined paragraph texts (oldest parser)
		java.util.Map<String, Integer> occByText = new java.util.HashMap<>();
		java.util.Map<String, Integer> occByOld = new java.util.HashMap<>();
		java.util.Map<String, Integer> occByLegacy = new java.util.HashMap<>();
		for (JsonElement stepEl : steps)
		{
			if (stepCount >= MAX_STEPS)
			{
				guide.markTruncated();
				continue;
			}
			if (!stepEl.isJsonObject())
			{
				continue;
			}
			JsonObject step = stepEl.getAsJsonObject();

			String raw = joinContent(optArray(step, "content"), false);
			String legacyRaw = joinContent(optArray(step, "content"), true);
			String itemsSuffix = itemsSuffix(step);
			// KNOWN BOUNDED EDGE: a step is added (and all occurrence counters
			// advanced) only when the NEW verbatim text is non-empty. If runs
			// concatenate into a fully-strippable tag while the old space-joined
			// form did not (pathological input), later duplicate-text legacy
			// keys shift and miss migration - the user re-ticks those steps.
			if (!raw.isEmpty())
			{
				stepCount = addParagraph(guide, bank, raw, legacyRaw, itemsSuffix, 0,
					videoLinks(optArray(step, "content")),
					occByText, occByOld, occByLegacy, stepCount);
			}

			JsonArray nested = optArray(step, "nestedContent");
			if (nested != null)
			{
				for (JsonElement nEl : nested)
				{
					if (stepCount >= MAX_STEPS || !nEl.isJsonObject())
					{
						continue;
					}
					JsonObject n = nEl.getAsJsonObject();
					String nRaw = joinContent(optArray(n, "content"), false);
					String nLegacy = joinContent(optArray(n, "content"), true);
					if (!nRaw.isEmpty())
					{
						int depth = Math.max(1, Math.min(4, optInt(n, "level", 1)));
						stepCount = addParagraph(guide, bank, nRaw, nLegacy, "", depth,
							videoLinks(optArray(n, "content")),
							occByText, occByOld, occByLegacy, stepCount);
					}
				}
			}
		}
		return stepCount;
	}

	/**
	 * Add one source paragraph as one step PER SENTENCE (the guide's paragraphs
	 * are strings of short actions; one checkbox per action reads like the
	 * wikitext guides and stops the MAX_TEXT cap from truncating the tail).
	 * Sentences still longer than LONG_FRAGMENT split again at comma
	 * boundaries, the continuation chunks indented one level.
	 *
	 * Both generations of the paragraph's OLD key are recorded for progress
	 * migration: the pre-split verbatim key maps to all split children via
	 * {@link Guide#getLegacySplitKeys()}, and the oldest space-joined key
	 * chains onto it via {@link Guide#getLegacyStepKeys()} (replayed first, so
	 * either generation of saved progress lands on the split steps).
	 */
	private static int addParagraph(Guide guide, GuideBank bank, String raw, String legacyRaw,
		String itemsSuffix, int depth, java.util.List<String[]> videoLinks,
		java.util.Map<String, Integer> occByText,
		java.util.Map<String, Integer> occByOld, java.util.Map<String, Integer> occByLegacy, int stepCount)
	{
		if (raw.length() > MAX_PARAGRAPH)
		{
			raw = raw.substring(0, MAX_PARAGRAPH - 1) + "…";
		}
		java.util.List<String> newKeys = new java.util.ArrayList<>();
		java.util.List<GuideStep> added = new java.util.ArrayList<>();
		boolean first = true;
		for (String fragment : splitDigestible(raw))
		{
			boolean fragmentHead = true;
			// the first fragment carries the items suffix: reserve room for it
			// so the combined text can never reach the MAX_TEXT cap
			for (String chunk : chunkAtCommas(fragment, first ? itemsSuffix.length() : 0))
			{
				if (stepCount >= MAX_STEPS)
				{
					break;
				}
				// the items suffix stays on the paragraph's first step only
				String text = first ? chunk + itemsSuffix : chunk;
				int d = fragmentHead ? depth : Math.min(4, depth + 1);
				GuideStep guideStep = addStep(bank, text, d, occByText);
				// bare video URLs in the text itself (some runs carry the URL
				// as their visible text)
				guideStep.setVideoUrl(VideoLinks.firstVideoUrl(guideStep.getText()));
				newKeys.add(guideStep.getKey());
				added.add(guideStep);
				stepCount++;
				first = false;
				fragmentHead = false;
			}
		}

		// linked runs ("Getting Rats for 2t Oaks" -> youtube): attach each
		// video to the split step whose text contains the anchor, falling back
		// to the paragraph's first step when the anchor got chunked apart
		for (String[] link : videoLinks)
		{
			String anchor = link[0];
			String needle = anchor.length() > 24 ? anchor.substring(0, 24) : anchor;
			GuideStep dest = null;
			if (!needle.isEmpty())
			{
				for (GuideStep s : added)
				{
					if (s.getText().contains(needle))
					{
						dest = s;
						break;
					}
				}
			}
			if (dest == null && !added.isEmpty())
			{
				dest = added.get(0);
			}
			if (dest != null && dest.getVideoUrl() == null)
			{
				dest.setVideoUrl(link[1]);
			}
		}

		// the key the PREVIOUS parser gave this paragraph (verbatim join,
		// suffix appended, both capped exactly as it did)
		//
		// KNOWN BOUNDED EDGE: a paragraph whose whole text equals another
		// paragraph's leading sentence gets an old key that a real split
		// child now owns. migrateSplitKeys resolves it when the store also
		// holds the other paragraph's old key; an old store holding ONLY the
		// duplicate is indistinguishable from new-format progress on the
		// child, so the identical-text child shows ticked in its place - the
		// user re-ticks at most one step per duplicated sentence.
		String oldText = cap(cap(raw) + itemsSuffix);
		int oldOcc = occByOld.merge(oldText, 1, Integer::sum) - 1;
		String oldKey = bank.getId() + "#" + Integer.toHexString(oldText.hashCode()) + "#" + oldOcc;
		if (!newKeys.isEmpty() && !(newKeys.size() == 1 && newKeys.get(0).equals(oldKey)))
		{
			guide.getLegacySplitKeys().put(oldKey, newKeys);
		}

		// the OLDEST parser joined formatting runs with spaces; its key chains
		// onto the previous parser's key (migrateLegacyKeys runs before
		// migrateSplitKeys, so gen1 -> gen2 -> split children resolves fully)
		String legacyText = cap(cap(legacyRaw) + itemsSuffix);
		int legacyOcc = occByLegacy.merge(legacyText, 1, Integer::sum) - 1;
		if (!legacyText.equals(oldText) || legacyOcc != oldOcc)
		{
			String legacyKey = bank.getId() + "#" + Integer.toHexString(legacyText.hashCode()) + "#" + legacyOcc;
			if (!legacyKey.equals(oldKey))
			{
				guide.getLegacyStepKeys().put(legacyKey, oldKey);
			}
		}
		return stepCount;
	}

	private static GuideStep addStep(GuideBank bank, String text, int depth, java.util.Map<String, Integer> occByText)
	{
		// enforce the documented cap on the COMBINED text (items suffix included)
		text = cap(text);
		int occ = occByText.merge(text, 1, Integer::sum) - 1;
		String key = bank.getId() + "#" + Integer.toHexString(text.hashCode()) + "#" + occ;
		GuideStep step = new GuideStep(key, text, depth, bank.getId());
		bank.getSteps().add(step);
		return step;
	}

	/**
	 * (anchor text, url) pairs for content runs that carry a whitelisted video
	 * link - the url lives either on the run itself or in its formatting
	 * object, depending on the exporter version.
	 */
	private static java.util.List<String[]> videoLinks(JsonArray content)
	{
		java.util.List<String[]> out = new java.util.ArrayList<>();
		if (content == null)
		{
			return out;
		}
		for (JsonElement el : content)
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject run = el.getAsJsonObject();
			String url = optString(run, "url", "");
			if (url.isEmpty())
			{
				JsonElement f = run.get("formatting");
				if (f != null && f.isJsonObject())
				{
					url = optString(f.getAsJsonObject(), "url", "");
				}
			}
			// store the ACCEPTED form: trimmed (an untrimmed original would
			// pass validation yet break LinkBrowser later) and https-normalized
			String ok = VideoLinks.accepted(url);
			if (ok != null)
			{
				out.add(new String[]{WikitextParser.sanitizeDisplay(optString(run, "text", "")), ok});
			}
		}
		return out;
	}

	/**
	 * Split a sanitized paragraph into sentences. A sentence ends at '.', '!'
	 * or '?' followed by a space, EXCEPT inside a SHORT parenthetical (see
	 * {@link #protectedSpans}) or after a known abbreviation. Fragments shorter
	 * than MIN_FRAGMENT fold into their neighbor so stray "Ok." pieces never
	 * become their own checkbox.
	 */
	static java.util.List<String> splitDigestible(String text)
	{
		boolean[] prot = protectedSpans(text);
		java.util.List<String> parts = new java.util.ArrayList<>();
		int start = 0;
		int n = text.length();
		for (int i = 0; i < n; i++)
		{
			char c = text.charAt(i);
			if ((c == '.' || c == '!' || c == '?') && !prot[i] && i + 1 < n
				&& text.charAt(i + 1) == ' ' && !abbreviationBefore(text, i))
			{
				String frag = text.substring(start, i + 1).trim();
				if (!frag.isEmpty())
				{
					parts.add(frag);
				}
				start = i + 1;
			}
		}
		String tail = text.substring(start).trim();
		if (!tail.isEmpty())
		{
			parts.add(tail);
		}
		java.util.List<String> merged = new java.util.ArrayList<>();
		for (String p : parts)
		{
			int last = merged.size() - 1;
			if (last >= 0 && (p.length() < MIN_FRAGMENT || merged.get(last).length() < MIN_FRAGMENT))
			{
				merged.set(last, merged.get(last) + " " + p);
			}
			else
			{
				merged.add(p);
			}
		}
		return merged;
	}

	/**
	 * Marks characters inside a SHORT parenthetical/bracket - one whose closer
	 * arrives within PAREN_WINDOW chars - as protected from splitting, so
	 * asides like "(hop worlds. Really)" stay on one step. An unclosed or
	 * page-long parenthetical is treated as ordinary prose: the real guide has
	 * paragraph-sized "(this involves...)" asides that would otherwise lock
	 * splitting off for the rest of the paragraph. Bounded work: each opener
	 * scans at most PAREN_WINDOW chars ahead.
	 */
	private static boolean[] protectedSpans(String text)
	{
		int n = text.length();
		boolean[] prot = new boolean[n];
		for (int i = 0; i < n; i++)
		{
			char c = text.charAt(i);
			if (c != '(' && c != '[')
			{
				continue;
			}
			char closer = c == '(' ? ')' : ']';
			int end = Math.min(n, i + 1 + PAREN_WINDOW);
			for (int j = i + 1; j < end; j++)
			{
				if (text.charAt(j) == closer)
				{
					for (int k = i; k <= j; k++)
					{
						prot[k] = true;
					}
					break;
				}
			}
		}
		return prot;
	}

	/** True when the token ending at the '.' at dotIdx is a known abbreviation. */
	private static boolean abbreviationBefore(String text, int dotIdx)
	{
		int j = dotIdx - 1;
		while (j >= 0 && text.charAt(j) != ' ')
		{
			j--;
		}
		return ABBREVIATIONS.contains(text.substring(j + 1, dotIdx).toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * Secondary split for a sentence still longer than LONG_FRAGMENT: cut at
	 * the first unprotected ", " once CHUNK_TARGET is reached, greedily. A
	 * chunk that STILL exceeds LONG_FRAGMENT (commas all inside parens, or no
	 * commas at all) falls back to cutting at any ", ", then at a plain space,
	 * so ordinary prose never survives long enough for the MAX_TEXT cap to
	 * truncate visible text. (A single space-less token longer than the cap
	 * has no cut point at all and still gets truncated - hostile input only.)
	 * A trailing chunk too short to stand alone folds into the previous one.
	 */
	static java.util.List<String> chunkAtCommas(String fragment)
	{
		return chunkAtCommas(fragment, 0);
	}

	/**
	 * @param reserve chars to keep free on the FIRST chunk (the items suffix
	 *                appended to a paragraph's first step), so suffix-carrying
	 *                steps can never reach the MAX_TEXT cap either
	 */
	static java.util.List<String> chunkAtCommas(String fragment, int reserve)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		if (fragment.length() + reserve <= LONG_FRAGMENT)
		{
			out.add(fragment);
			return out;
		}
		boolean[] prot = protectedSpans(fragment);
		int start = 0;
		for (int i = 0; i < fragment.length(); i++)
		{
			if (fragment.charAt(i) == ',' && !prot[i] && i + 1 < fragment.length()
				&& fragment.charAt(i + 1) == ' ' && i + 1 - start >= CHUNK_TARGET)
			{
				out.add(fragment.substring(start, i + 1).trim());
				start = i + 1;
			}
		}
		String tail = fragment.substring(start).trim();
		if (!tail.isEmpty())
		{
			int last = out.size() - 1;
			if (tail.length() < MIN_FRAGMENT && last >= 0)
			{
				out.set(last, out.get(last) + " " + tail);
			}
			else
			{
				out.add(tail);
			}
		}
		// fallback tiers for chunks the protected commas couldn't shorten
		java.util.List<String> bounded = new java.util.ArrayList<>();
		for (String chunk : out)
		{
			while (true)
			{
				int limit = bounded.isEmpty() ? LONG_FRAGMENT - reserve : LONG_FRAGMENT;
				if (chunk.length() <= limit)
				{
					break;
				}
				int cut = chunk.lastIndexOf(", ", limit);
				if (cut < MIN_FRAGMENT)
				{
					cut = chunk.lastIndexOf(' ', limit);
				}
				if (cut < MIN_FRAGMENT)
				{
					break; // one giant unbreakable token: let the cap handle it
				}
				bounded.add(chunk.substring(0, cut + 1).trim());
				chunk = chunk.substring(cut + 1).trim();
			}
			if (!chunk.isEmpty())
			{
				int last = bounded.size() - 1;
				if (chunk.length() < MIN_FRAGMENT && last >= 0)
				{
					bounded.set(last, bounded.get(last) + " " + chunk);
				}
				else
				{
					bounded.add(chunk);
				}
			}
		}
		return bounded;
	}

	private static String cap(String s)
	{
		return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT - 1) + "…" : s;
	}

	/**
	 * Concatenate a content array's text fields into one clean, sanitized step
	 * line. Runs are FORMATTING runs and are frequently split mid-word
	 * ("Gr" + "ab coins"), so they concatenate verbatim - each run carries its
	 * own spacing. legacySpacing reproduces the old space-inserting behavior
	 * solely so progress keys recorded under it can be migrated.
	 */
	private static String joinContent(JsonArray content, boolean legacySpacing)
	{
		if (content == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (JsonElement el : content)
		{
			if (el.isJsonObject())
			{
				String t = optString(el.getAsJsonObject(), "text", "");
				if (!t.isEmpty())
				{
					if (legacySpacing && sb.length() > 0)
					{
						sb.append(' ');
					}
					sb.append(t);
				}
			}
			else if (el.isJsonPrimitive())
			{
				sb.append(el.getAsString());
			}
		}
		// NOT capped here: the sentence splitter needs the full paragraph.
		// Old-parser keys re-apply the cap via cap() where they are rebuilt.
		return WikitextParser.sanitizeDisplay(sb.toString());
	}

	/** " (Items: ...)" from metadata.items_needed when it's a real list. */
	private static String itemsSuffix(JsonObject step)
	{
		JsonElement m = step.get("metadata");
		if (m == null || !m.isJsonObject())
		{
			return "";
		}
		String items = optString(m.getAsJsonObject(), "items_needed", "");
		if (items.isEmpty() || items.equalsIgnoreCase("none") || items.equalsIgnoreCase("n/a"))
		{
			return "";
		}
		String clean = WikitextParser.sanitizeDisplay(items);
		if (clean.length() > 120)
		{
			clean = clean.substring(0, 119) + "…";
		}
		return " (Items: " + clean + ")";
	}

	private static JsonArray optArray(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	private static String optString(JsonObject o, String key, String fallback)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}

	private static int optInt(JsonObject o, String key, int fallback)
	{
		try
		{
			JsonElement e = o.get(key);
			return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
		}
		catch (Exception ex)
		{
			return fallback;
		}
	}
}
