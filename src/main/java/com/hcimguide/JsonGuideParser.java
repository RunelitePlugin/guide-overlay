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
	private static final int MAX_TEXT = 400;

	/** True when the text is a JSON guide this parser understands. */
	public static boolean looksLikeJsonGuide(String text)
	{
		if (text == null)
		{
			return false;
		}
		String t = text.trim();
		if (t.isEmpty() || t.charAt(0) != '{')
		{
			return false;
		}
		try
		{
			// instance API, not JsonParser.parseString: the client bundles an
			// older gson where the static method does not exist
			JsonElement root = new JsonParser().parse(t);
			return root.isJsonObject() && root.getAsJsonObject().has("chapters");
		}
		catch (Exception e)
		{
			return false;
		}
	}

	public Guide parse(String text)
	{
		Guide guide = new Guide();
		if (text == null)
		{
			return guide;
		}
		JsonObject root;
		try
		{
			JsonElement parsed = new JsonParser().parse(text);
			if (!parsed.isJsonObject())
			{
				return guide;
			}
			root = parsed.getAsJsonObject();
		}
		catch (Exception e)
		{
			return guide;
		}

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
			GuideEpisode episode = new GuideEpisode(chapterNo, WikitextParser.sanitizeDisplay(optString(chapter, "title", "Chapter " + chapterNo)));
			guide.getEpisodes().add(episode);

			// a chapter may hold sections, or steps directly
			JsonArray sections = optArray(chapter, "sections");
			if (sections == null)
			{
				JsonArray directSteps = optArray(chapter, "steps");
				if (directSteps != null)
				{
					GuideBank bank = new GuideBank("C" + chapterNo + ".S0", "Steps", chapterNo);
					episode.getBanks().add(bank);
					stepCount = addSteps(bank, directSteps, stepCount);
				}
				continue;
			}

			int sectionNo = 0;
			for (JsonElement sectionEl : sections)
			{
				if (!sectionEl.isJsonObject())
				{
					continue;
				}
				JsonObject section = sectionEl.getAsJsonObject();
				sectionNo++;
				String title = WikitextParser.sanitizeDisplay(optString(section, "title", "Section " + sectionNo));
				GuideBank bank = new GuideBank("C" + chapterNo + ".S" + sectionNo, title, chapterNo);
				episode.getBanks().add(bank);
				stepCount = addSteps(bank, optArray(section, "steps"), stepCount);
			}
		}
		return guide;
	}

	private int addSteps(GuideBank bank, JsonArray steps, int stepCount)
	{
		if (steps == null)
		{
			return stepCount;
		}
		// occurrence index counts IDENTICAL texts only (same contract as
		// WikitextParser): keys stay stable when unrelated steps are inserted
		// or removed upstream, so a re-import never resets unrelated progress
		java.util.Map<String, Integer> occByText = new java.util.HashMap<>();
		for (JsonElement stepEl : steps)
		{
			if (stepCount >= MAX_STEPS || !stepEl.isJsonObject())
			{
				continue;
			}
			JsonObject step = stepEl.getAsJsonObject();

			String text = joinContent(optArray(step, "content"));
			String itemsSuffix = itemsSuffix(step);
			if (!text.isEmpty())
			{
				addStep(bank, text + itemsSuffix, 0, occByText);
				stepCount++;
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
					String nText = joinContent(optArray(n, "content"));
					if (!nText.isEmpty())
					{
						int depth = Math.max(1, Math.min(4, optInt(n, "level", 1)));
						addStep(bank, nText, depth, occByText);
						stepCount++;
					}
				}
			}
		}
		return stepCount;
	}

	private static void addStep(GuideBank bank, String text, int depth, java.util.Map<String, Integer> occByText)
	{
		// enforce the documented cap on the COMBINED text (items suffix included)
		if (text.length() > MAX_TEXT)
		{
			text = text.substring(0, MAX_TEXT - 1) + "…";
		}
		int occ = occByText.merge(text, 1, Integer::sum) - 1;
		String key = bank.getId() + "#" + Integer.toHexString(text.hashCode()) + "#" + occ;
		bank.getSteps().add(new GuideStep(key, text, depth, bank.getId()));
	}

	/** Concatenate a content array's text fields into one clean, sanitized step line. */
	private static String joinContent(JsonArray content)
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
					if (sb.length() > 0)
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
		String out = WikitextParser.sanitizeDisplay(sb.toString());
		if (out.length() > MAX_TEXT)
		{
			out = out.substring(0, MAX_TEXT - 1) + "…";
		}
		return out;
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
