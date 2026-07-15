package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
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

	private final Gson gson;
	/** normalized NPC name -> {x, y, plane}. */
	private final Map<String, int[]> locations = new ConcurrentHashMap<>();
	private volatile boolean dirty;
	private volatile boolean loaded;

	/**
	 * lookup() runs every game tick while a pinned target is out of scene, and
	 * its loose-match fallback scans the whole map on a miss - trivial for the
	 * seed, but a bundled full-game database makes that tens of thousands of
	 * comparisons per tick. Memoize the last query (including misses) and
	 * invalidate whenever the map changes.
	 */
	private volatile String cachedQuery;
	private volatile WorldPoint cachedResult;

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
				String json = new String(Files.readAllBytes(STORE_FILE.toPath()), StandardCharsets.UTF_8);
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
				locations.putIfAbsent(Names.normalize(e.getKey()), v);
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
		if (norm.equals(cachedQuery))
		{
			return cachedResult;
		}
		int[] v = locations.get(norm);
		if (v == null)
		{
			// loose fallback for prefix-style matches ("Veos" vs "Veos the captain")
			for (Map.Entry<String, int[]> e : locations.entrySet())
			{
				if (Names.matchNormalized(norm, e.getKey()))
				{
					v = e.getValue();
					break;
				}
			}
		}
		WorldPoint result = v == null ? null : new WorldPoint(v[0], v[1], v[2]);
		cachedResult = result;
		cachedQuery = norm;
		return result;
	}

	private void invalidateLookupCache()
	{
		cachedQuery = null;
		cachedResult = null;
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
			Files.write(STORE_FILE.toPath(),
				gson.toJson(new HashMap<>(locations)).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			dirty = true;
			log.warn("Could not save npc location store", e);
		}
	}
}
