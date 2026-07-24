package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;

/**
 * Resolves transport shorthand independently from ordinary named places.
 *
 * <p>Transport aliases are deliberately contextual. A fairy-ring code is only
 * accepted next to an actual fairy-ring/access phrase, and item names such as
 * "Digsite pendant" only resolve when used as a travel instruction. This keeps
 * short codes and teleport items from becoming false location matches in bank
 * or equipment lists.</p>
 */
@Singleton
final class TransportResolver
{
	private static final String RESOURCE = "transport-destinations.json";
	private static final Type ENTRY_LIST = new TypeToken<List<Entry>>() { }.getType();
	private static final Pattern FAIRY_CODE = Pattern.compile("(?i)[a-d][i-l][p-s]");

	private static final Pattern TRANSPORT_INTENT = Pattern.compile(
		"(?i)\\b(?:tele(?:port)?(?:ed|ing|s)?|home tele(?:port)?|minigame tele(?:port)?|fairy ring|"
			+ "spirit tree|gnome glider|glider|minecart|quetzal|charter ship|charter|"
			+ "(?:take|enter|use|go through) (?:the |a |an |your )?"
			+ "(?:boat|ship|portal|minecart|quetzal|canoe|glider)|"
			+ "(?:boat|ship|canoe|minecart|quetzal|glider) to|sail|"
			+ "portal nexus|poh portal|house portal)\\b");
	private static final Pattern TRANSPORT_ITEM_ACTION = Pattern.compile(
		"(?i)(?:\\b(?:use|rub|activate|teleport with|travel with)\\s+"
			+ "(?:(?:the|a|an|your)\\s+)?"
			+ "(?:ardy cloak|ardougne cloak|ring of dueling|dueling ring|games necklace|"
			+ "amulet of glory|glory|skills necklace|combat bracelet|necklace of passage|"
			+ "digsite pendant|camulet|ectophial|chronicle|teleport crystal|giantsoul amulet|"
			+ "chasm teleport scroll|kharedst's memoirs|kharedsts memoirs|book of the dead)\\b"
			+ "|\\b(?:ardy cloak|ardougne cloak|ring of dueling|dueling ring|games necklace|"
			+ "amulet of glory|glory|skills necklace|combat bracelet|necklace of passage|"
			+ "digsite pendant|camulet|ectophial|chronicle|teleport crystal|giantsoul amulet|"
			+ "chasm teleport scroll|kharedst's memoirs|kharedsts memoirs|book of the dead)\\b"
			+ "[^.\\n]{0,60}?(?:->|→|\\bto\\b|\\btele(?:port)?\\b))");

	private static final Pattern FAIRY_CODE_AFTER_CONTEXT = Pattern.compile(
		"(?i)\\b(?:fairy ring|ardy cloak|ardougne cloak|poh|player owned house|"
			+ "house fairy ring|quest cape|slayer ring)\\b[^a-z0-9]{0,12}"
			+ "(?:to|code|then|use|via)?[^a-z0-9]{0,12}\\b([a-d][i-l][p-s])\\b");
	private static final Pattern FAIRY_CODE_BEFORE_CONTEXT = Pattern.compile(
		"(?i)\\b([a-d][i-l][p-s])\\b[^a-z0-9]{0,12}"
			+ "(?:fairy ring|fairy code)\\b");

	private final List<Destination> destinations;
	private final Map<String, Destination> fairyRings;

	@Inject
	TransportResolver(Gson gson)
	{
		List<Destination> loaded = new ArrayList<>();
		Map<String, Destination> rings = new HashMap<>();
		try (InputStream in = TransportResolver.class.getResourceAsStream(RESOURCE))
		{
			if (in != null)
			{
				List<Entry> entries = gson.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), ENTRY_LIST);
				if (entries != null)
				{
					for (Entry entry : entries)
					{
						Destination destination = Destination.from(entry);
						if (destination == null)
						{
							continue;
						}
						loaded.add(destination);
						if (destination.code != null)
						{
							rings.put(destination.code, destination);
						}
					}
				}
			}
		}
		catch (Exception ignored)
		{
			// Optional transport data must never prevent plugin startup.
		}
		destinations = Collections.unmodifiableList(loaded);
		fairyRings = Collections.unmodifiableMap(rings);
	}

	StepLocationHint find(String stepText)
	{
		if (stepText == null || stepText.trim().isEmpty())
		{
			return null;
		}

		Destination ring = findFairyRing(stepText);
		if (ring != null)
		{
			return ring.hint();
		}

		String normalized = PlaceDirectory.normalizeWords(stepText);
		if (normalized.isEmpty())
		{
			return null;
		}
		String haystack = " " + normalized + " ";
		Destination best = null;
		int bestEnd = -1;
		int bestLength = -1;
		for (Destination destination : destinations)
		{
			for (String phrase : destination.phrases)
			{
				String needle = " " + phrase + " ";
				int index = haystack.lastIndexOf(needle);
				if (index < 0)
				{
					continue;
				}
				int end = index + needle.length();
				if (end > bestEnd || (end == bestEnd && phrase.length() > bestLength))
				{
					best = destination;
					bestEnd = end;
					bestLength = phrase.length();
				}
			}
		}
		return best == null ? null : best.hint();
	}

	private Destination findFairyRing(String stepText)
	{
		Matcher after = FAIRY_CODE_AFTER_CONTEXT.matcher(stepText);
		Destination found = null;
		while (after.find())
		{
			Destination candidate = fairyRings.get(after.group(1).toUpperCase(Locale.ROOT));
			if (candidate != null)
			{
				found = candidate;
			}
		}
		if (found != null)
		{
			return found;
		}

		Matcher before = FAIRY_CODE_BEFORE_CONTEXT.matcher(stepText);
		while (before.find())
		{
			Destination candidate = fairyRings.get(before.group(1).toUpperCase(Locale.ROOT));
			if (candidate != null)
			{
				found = candidate;
			}
		}
		return found;
	}

	static boolean hasTransportIntent(String stepText)
	{
		if (stepText == null || stepText.isEmpty())
		{
			return false;
		}
		return TRANSPORT_INTENT.matcher(stepText).find()
			|| TRANSPORT_ITEM_ACTION.matcher(stepText).find()
			|| FAIRY_CODE_AFTER_CONTEXT.matcher(stepText).find()
			|| FAIRY_CODE_BEFORE_CONTEXT.matcher(stepText).find();
	}

	private static final class Destination
	{
		private final String name;
		private final String code;
		private final WorldPoint point;
		private final List<String> phrases;

		private Destination(String name, String code, WorldPoint point, List<String> phrases)
		{
			this.name = name;
			this.code = code;
			this.point = point;
			this.phrases = phrases;
		}

		private StepLocationHint hint()
		{
			String id = code != null ? "transport:fairy-ring:" + code.toLowerCase(Locale.ROOT)
				: "transport:" + PlaceDirectory.normalizeWords(name).replace(' ', '-');
			return new StepLocationHint(name, point, false, true, true, id,
				LocationSource.TRANSPORT, LocationConfidence.EXACT, 4, 2);
		}

		private static Destination from(Entry entry)
		{
			if (entry == null || entry.name == null || entry.name.trim().isEmpty()
				|| entry.x <= 0 || entry.y <= 0 || entry.plane < 0 || entry.plane > 3)
			{
				return null;
			}
			String code = null;
			if (entry.code != null && FAIRY_CODE.matcher(entry.code).matches())
			{
				code = entry.code.toUpperCase(Locale.ROOT);
			}
			List<String> phrases = new ArrayList<>();
			if (entry.phrases != null)
			{
				for (String value : entry.phrases)
				{
					String phrase = PlaceDirectory.normalizeWords(value);
					if (phrase.length() >= 5 && !phrases.contains(phrase))
					{
						phrases.add(phrase);
					}
				}
			}
			if (code == null && phrases.isEmpty())
			{
				return null;
			}
			return new Destination(entry.name.trim(), code,
				new WorldPoint(entry.x, entry.y, entry.plane),
				Collections.unmodifiableList(phrases));
		}
	}

	private static final class Entry
	{
		private String name;
		private String code;
		private List<String> phrases;
		private int x;
		private int y;
		private int plane;
	}
}
