package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NPC name -&gt; world location database used to point at far-away targets.
 *
 * Two sources, no guesswork at runtime:
 * - a small bundled seed of well-known early-game NPCs (approximate is fine -
 *   the arrow only needs to point the right way);
 * - LEARNING: whenever a guide-target NPC is actually seen in the scene, its
 *   exact position is recorded, overriding the seed. The database therefore
 *   self-corrects and grows as you play, and is bounded by the set of names
 *   the guide references.
 *
 * Persisted to ~/.runelite/hcim-guide/npc-locations.json.
 */
@Singleton
public class NpcLocationStore
{
	private static final Logger log = LoggerFactory.getLogger(NpcLocationStore.class);
	private static final File STORE_FILE =
		new File(new File(RuneLite.RUNELITE_DIR, "hcim-guide"), "npc-locations.json");
	private static final Type MAP_TYPE = new TypeToken<Map<String, int[]>>()
	{
	}.getType();

	/** Import size guard: covers full-game spawn dumps with generous headroom. */
	private static final int MAX_IMPORT_ENTRIES = 50_000;
	private static final int MAX_IMPORT_CHARS = 16 * 1024 * 1024;
	private static final long MAX_STORE_BYTES = 32L * 1024 * 1024;

	private final Gson gson;
	/** normalized NPC name -> {x, y, plane}. */
	private final Map<String, int[]> locations = new ConcurrentHashMap<>();
	private volatile boolean dirty;
	private volatile boolean loaded;

	/**
	 * lookup() runs every game tick while a pinned target is out of scene, and
	 * its loose-match fallback scans the whole map on a miss - trivial for the
	 * seed, but a bundled full-game database makes that tens of thousands of
	 * comparisons per tick. Memoize recent queries INCLUDING misses (an empty
	 * Optional), so alternating between two unknown targets never rescans, and
	 * invalidate whenever the map changes. Access-order LRU, synchronized;
	 * clear() replaces the old two-field cache whose separate writes could
	 * briefly pair a query with the wrong result.
	 */
	private static final int LOOKUP_CACHE_SIZE = 16;
	private final Map<String, Optional<WorldPoint>> lookupCache =
		Collections.synchronizedMap(new LinkedHashMap<String, Optional<WorldPoint>>(32, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Optional<WorldPoint>> eldest)
			{
				return size() > LOOKUP_CACHE_SIZE;
			}
		});

	@Inject
	public NpcLocationStore(Gson gson)
	{
		this.gson = gson;
	}

	/** Loads the on-disk store (seeded from the bundled resources on first run). Call off the EDT. */
	public synchronized void load()
	{
		if (loaded)
		{
			return;
		}
		// precedence: anything already learned this session (newest) > the
		// saved store > the bundled FULL database (if present) > the seed -
		// so neither file load nor bundled data can overwrite a position
		// observed moments ago
		if (STORE_FILE.isFile())
		{
			try
			{
				String json = readStoreText();
				mergeIfAbsent(gson.fromJson(json, MAP_TYPE));
			}
			catch (Exception e)
			{
				log.warn("Could not read npc location store", e);
			}
		}
		// optional full-game database: generate with tools/convert_spawns.py
		// from a community spawn dump and drop it into resources before
		// building - the plugin bundles and loads it automatically
		readInto(resourceStream("npc-locations-full.json"), "bundled full database");
		readInto(resourceStream("npc-locations-seed.json"), "bundled seed");
		invalidateLookupCache();
		loaded = true;
	}

	private InputStream resourceStream(String name)
	{
		return NpcLocationStore.class.getResourceAsStream(name);
	}

	private void readInto(InputStream in, String label)
	{
		if (in == null)
		{
			return;
		}
		try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			mergeIfAbsent(gson.fromJson(reader, MAP_TYPE));
		}
		catch (Exception e)
		{
			log.warn("Could not read npc locations ({})", label, e);
		}
	}

	private void mergeIfAbsent(Map<String, int[]> raw)
	{
		if (raw == null)
		{
			return;
		}
		for (Map.Entry<String, int[]> e : raw.entrySet())
		{
			int[] v = e.getValue();
			// same range check as importJson: a corrupted on-disk store must
			// not feed nonsense coordinates to the compass/world map
			if (e.getKey() != null && v != null && v.length == 3
				&& v[0] >= 0 && v[0] < 20000 && v[1] >= 0 && v[1] < 20000 && v[2] >= 0 && v[2] <= 3)
			{
				String key = Names.normalize(e.getKey());
				if (!key.isEmpty())
				{
					locations.putIfAbsent(key, v);
				}
			}
		}
	}

	/** Records an observed NPC position (exact data always overrides the seed). */
	public void learn(String npcName, WorldPoint point)
	{
		if (npcName == null || point == null)
		{
			return;
		}
		String key = Names.normalize(npcName);
		if (key.isEmpty())
		{
			return;
		}
		int[] existing = locations.get(key);
		if (existing != null && existing[0] == point.getX() && existing[1] == point.getY()
			&& existing[2] == point.getPlane())
		{
			return;
		}
		locations.put(key, new int[]{point.getX(), point.getY(), point.getPlane()});
		invalidateLookupCache();
		dirty = true;
	}

	/** Best-known location for a target name, or null. Memoized per query. */
	public WorldPoint lookup(String targetName)
	{
		String norm = Names.normalize(targetName);
		if (norm.isEmpty())
		{
			return null;
		}
		Optional<WorldPoint> cached = lookupCache.get(norm);
		if (cached != null)
		{
			return cached.orElse(null);
		}
		int[] v = locations.get(norm);
		if (v == null)
		{
			v = looseMatch(norm);
		}
		WorldPoint result = v == null ? null : new WorldPoint(v[0], v[1], v[2]);
		lookupCache.put(norm, Optional.ofNullable(result));
		return result;
	}

	/**
	 * Deterministic loose fallback for prefix-style matches ("Veos" vs "Veos
	 * the captain"), used only after an exact miss. Prefix matching is only
	 * attempted when both names have at least four normalized characters -
	 * a two-letter query would loose-match half of a full-game database.
	 * Among the candidates the closest length wins, ties broken
	 * lexicographically, so the same query resolves to the same entry
	 * regardless of map iteration order or capacity.
	 */
	private int[] looseMatch(String norm)
	{
		if (norm.length() < 4)
		{
			return null;
		}
		String bestKey = null;
		int[] best = null;
		for (Map.Entry<String, int[]> e : locations.entrySet())
		{
			String key = e.getKey();
			if (key.length() < 4 || !Names.matchNormalized(norm, key))
			{
				continue;
			}
			if (bestKey == null || closerTo(norm, key, bestKey))
			{
				bestKey = key;
				best = e.getValue();
			}
		}
		return best;
	}

	/** True when candidate is a strictly better match for query than incumbent. */
	private static boolean closerTo(String query, String candidate, String incumbent)
	{
		int c = Math.abs(candidate.length() - query.length());
		int i = Math.abs(incumbent.length() - query.length());
		return c != i ? c < i : candidate.compareTo(incumbent) < 0;
	}

	private void invalidateLookupCache()
	{
		lookupCache.clear();
	}

	/** All known locations as shareable JSON (name -&gt; [x, y, plane]). */
	public String exportJson()
	{
		return gson.toJson(new java.util.TreeMap<>(locations));
	}

	/**
	 * Merges locations from shared JSON (an {@link #exportJson()} payload or a
	 * converted community spawn dump). Explicit user action, so imported
	 * entries overwrite existing ones.
	 *
	 * @return number of entries imported
	 * @throws IllegalArgumentException when the text isn't a valid location map
	 */
	public int importJson(String json)
	{
		if (json == null)
		{
			throw new IllegalArgumentException("No locations found in the text");
		}
		if (json.length() > MAX_IMPORT_CHARS)
		{
			throw new IllegalArgumentException("Location JSON is too large (over 16MB)");
		}
		if (json.trim().isEmpty())
		{
			throw new IllegalArgumentException("No locations found in the text");
		}
		Map<String, int[]> raw;
		try
		{
			raw = gson.fromJson(json, MAP_TYPE);
		}
		catch (Exception e)
		{
			throw new IllegalArgumentException("Not a valid location JSON: " + e.getMessage(), e);
		}
		if (raw == null || raw.isEmpty())
		{
			throw new IllegalArgumentException("No locations found in the text");
		}
		if (raw.size() > MAX_IMPORT_ENTRIES)
		{
			throw new IllegalArgumentException(
				"Too many entries (" + raw.size() + "); the limit is " + MAX_IMPORT_ENTRIES);
		}
		int count = 0;
		for (Map.Entry<String, int[]> e : raw.entrySet())
		{
			int[] v = e.getValue();
			if (e.getKey() != null && v != null && v.length == 3
				&& v[0] >= 0 && v[0] < 20000 && v[1] >= 0 && v[1] < 20000 && v[2] >= 0 && v[2] <= 3)
			{
				String key = Names.normalize(e.getKey());
				if (!key.isEmpty())
				{
					locations.put(key, v);
					count++;
				}
			}
		}
		if (count == 0)
		{
			throw new IllegalArgumentException("No valid location entries in the text");
		}
		invalidateLookupCache();
		dirty = true;
		return count;
	}

	/**
	 * Bulk-adds entries whose keys are ALREADY normalized, without overwriting
	 * anything present (learned/saved/seed positions win). Used by the full
	 * database download so observed data is never clobbered.
	 *
	 * @return number of new entries added
	 */
	public int addNormalizedIfAbsent(Map<String, int[]> normalized)
	{
		int added = 0;
		for (Map.Entry<String, int[]> e : normalized.entrySet())
		{
			int[] v = e.getValue();
			if (e.getKey() != null && !e.getKey().isEmpty() && v != null && v.length == 3
				&& v[0] >= 0 && v[0] < 20000 && v[1] >= 0 && v[1] < 20000 && v[2] >= 0 && v[2] <= 3
				&& locations.putIfAbsent(e.getKey(), v) == null)
			{
				added++;
			}
		}
		if (added > 0)
		{
			invalidateLookupCache();
			dirty = true;
		}
		return added;
	}

	/** Writes the store if anything changed. Call off the EDT/client thread. */
	public synchronized void saveIfDirty()
	{
		if (!dirty)
		{
			return;
		}
		// clear BEFORE snapshotting: a learn() landing mid-save re-marks dirty
		// and gets picked up by the next save instead of being lost
		dirty = false;
		try
		{
			//noinspection ResultOfMethodCallIgnored
			STORE_FILE.getParentFile().mkdirs();
			atomicWrite(STORE_FILE,
				gson.toJson(new HashMap<>(locations)).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			dirty = true;
			log.warn("Could not save npc location store", e);
		}
	}

	private static void atomicWrite(File target, byte[] bytes) throws IOException
	{
		Path directory = target.getParentFile().toPath();
		Files.createDirectories(directory);
		Path temp = Files.createTempFile(directory, target.getName(), ".tmp");
		try
		{
			Files.write(temp, bytes);
			try
			{
				Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally
		{
			Files.deleteIfExists(temp);
		}
	}

	private static String readStoreText() throws IOException
	{
		try (InputStream in = Files.newInputStream(STORE_FILE.toPath()))
		{
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) > 0)
			{
				out.write(buffer, 0, n);
				if (out.size() > MAX_STORE_BYTES)
				{
					throw new IOException("location store exceeds 32MB");
				}
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
