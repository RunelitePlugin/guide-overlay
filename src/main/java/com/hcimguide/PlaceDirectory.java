package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;

/**
 * Curated named destinations used when a guide step names a place rather than
 * an NPC. Entries live in a data file so new aliases or destinations can be
 * added without changing parser logic.
 */
@Singleton
final class PlaceDirectory
{
	private static final String RESOURCE = "place-locations-seed.json";
	private static final Type ENTRY_LIST = new TypeToken<List<Entry>>() { }.getType();
	private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9']+");
	private static final Pattern SPACES = Pattern.compile("\\s+");
	private static final Pattern MOVEMENT_INTENT = Pattern.compile(
		"(?i)\\b(?:go|head|travel|teleport|run|walk|return|sail|enter|leave|"
			+ "climb|descend|make your way|take (?:the )?(?:boat|ship|portal|minecart|quetzal|canoe|charter)|"
			+ "minigame teleport|fairy ring|ardy cloak|ardougne cloak)\\b");
	private static final Pattern NON_MOVEMENT_LOCATION_CONTEXT = Pattern.compile(
		"(?i)\\b(?:do|visit|reach|bank|start|finish|complete|train|mine|fish|hunt|kill|"
			+ "buy|talk|at|in|inside|outside|near|from|to)(?:\\s+the)?\\s+$");

	private final List<Place> places;

	@Inject
	PlaceDirectory(Gson gson)
	{
		List<Place> loaded = new ArrayList<>();
		try (InputStream in = PlaceDirectory.class.getResourceAsStream(RESOURCE))
		{
			if (in != null)
			{
				List<Entry> entries = gson.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), ENTRY_LIST);
				if (entries != null)
				{
					for (Entry entry : entries)
					{
						Place place = Place.from(entry);
						if (place != null)
						{
							loaded.add(place);
						}
					}
				}
			}
		}
		catch (Exception ignored)
		{
			// A missing or malformed optional seed must not prevent plugin startup.
		}
		places = Collections.unmodifiableList(loaded);
	}

	/**
	 * Returns the last explicitly named destination in a step. Choosing the
	 * final mention matches common guide wording such as "go to X, then Y";
	 * ties prefer the longer alias ("Varrock East Bank" over "Varrock").
	 */
	StepLocationHint find(String stepText)
	{
		String normalized = normalizeWords(stepText);
		String haystack = " " + normalized + " ";
		if (normalized.isEmpty())
		{
			return null;
		}
		// If a later travel verb names a destination the directory does not
		// know, an earlier known place is stale. Only accept a place mentioned
		// after the final movement instruction (or anywhere when there is none).
		int lastMovementEnd = lastMovementIntentEnd(normalized);
		Place bestPlace = null;
		int bestEnd = -1;
		int bestLength = -1;
		for (Place place : places)
		{
			for (String alias : place.aliases)
			{
				String needle = " " + alias + " ";
				int index = haystack.lastIndexOf(needle);
				if (index < 0 || (lastMovementEnd >= 0 && index < lastMovementEnd)
					|| (lastMovementEnd < 0 && alias.indexOf(' ') < 0
						&& !hasNonMovementLocationContext(normalized, index)))
				{
					continue;
				}
				int end = index + needle.length();
				if (end > bestEnd || (end == bestEnd && alias.length() > bestLength))
				{
					bestPlace = place;
					bestEnd = end;
					bestLength = alias.length();
				}
			}
		}
		return bestPlace == null ? null
			: new StepLocationHint(bestPlace.name, bestPlace.point, false, false, false,
				"place:" + normalizeWords(bestPlace.name).replace(' ', '-'),
				LocationSource.NAMED_PLACE, LocationConfidence.EXACT, 5, 2);
	}

	private static boolean hasNonMovementLocationContext(String normalized, int aliasStart)
	{
		int from = Math.max(0, aliasStart - 40);
		return NON_MOVEMENT_LOCATION_CONTEXT.matcher(normalized.substring(from, aliasStart)).find();
	}

	private static int lastMovementIntentEnd(String normalized)
	{
		java.util.regex.Matcher matcher = MOVEMENT_INTENT.matcher(normalized);
		int end = -1;
		while (matcher.find())
		{
			end = matcher.end();
		}
		return end;
	}

	static boolean hasMovementIntent(String stepText)
	{
		return stepText != null && MOVEMENT_INTENT.matcher(stepText).find();
	}

	static String normalizeWords(String value)
	{
		if (value == null)
		{
			return "";
		}
		String lower = value.toLowerCase(Locale.ROOT)
			.replace('\u2019', '\'')
			.replace('\u2018', '\'');
		return SPACES.matcher(NON_WORD.matcher(lower).replaceAll(" ")).replaceAll(" ").trim();
	}

	private static final class Place
	{
		private final String name;
		private final WorldPoint point;
		private final List<String> aliases;

		private Place(String name, WorldPoint point, List<String> aliases)
		{
			this.name = name;
			this.point = point;
			this.aliases = aliases;
		}

		private static Place from(Entry entry)
		{
			if (entry == null || entry.name == null || entry.name.trim().isEmpty()
				|| entry.x <= 0 || entry.y <= 0 || entry.plane < 0 || entry.plane > 3)
			{
				return null;
			}
			List<String> aliases = new ArrayList<>();
			addAlias(aliases, entry.name);
			if (entry.aliases != null)
			{
				for (String alias : entry.aliases)
				{
					addAlias(aliases, alias);
				}
			}
			return aliases.isEmpty() ? null : new Place(entry.name.trim(),
				new WorldPoint(entry.x, entry.y, entry.plane), Collections.unmodifiableList(aliases));
		}

		private static void addAlias(List<String> aliases, String value)
		{
			String alias = normalizeWords(value);
			// Very short aliases create accidental matches in ordinary prose.
			if (alias.length() >= 4 && !aliases.contains(alias))
			{
				aliases.add(alias);
			}
		}
	}

	private static final class Entry
	{
		private String name;
		private List<String> aliases;
		private int x;
		private int y;
		private int plane;
	}
}
