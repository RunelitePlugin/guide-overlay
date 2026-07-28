package com.hcimguide;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

/**
 * Best-effort item name -&gt; item id resolution for icon display.
 *
 * Strategy: a small curated map of common early-game untradeables first
 * (ItemManager.search only covers tradeable items), then ItemManager.search.
 * Unresolvable names get -1; the grid shows a text chip instead and
 * inventory presence checking still works by name.
 */
@Singleton
public class ItemIconResolver
{
	private static final Logger log = LoggerFactory.getLogger(ItemIconResolver.class);

	/**
	 * Long-stable ids for items the guide references that the price-based
	 * search cannot resolve (untradeables and currency).
	 */
	private static final Map<String, Integer> KNOWN_IDS = new HashMap<>();

	static
	{
		KNOWN_IDS.put("coins", 995);
		KNOWN_IDS.put("spade", 952);
		KNOWN_IDS.put("tinderbox", 590);
		KNOWN_IDS.put("knife", 946);
		KNOWN_IDS.put("bread", 2309);
		KNOWN_IDS.put("bucket", 1925);
		KNOWN_IDS.put("bowl", 1923);
		KNOWN_IDS.put("jug", 1935);
		KNOWN_IDS.put("emptyjug", 1935);
		KNOWN_IDS.put("feather", 314);
		KNOWN_IDS.put("logs", 1511);
		KNOWN_IDS.put("bones", 526);
		KNOWN_IDS.put("beer", 1917);
		KNOWN_IDS.put("cheese", 1985);
		KNOWN_IDS.put("garlic", 1550);
		KNOWN_IDS.put("leathergloves", 1059);
		KNOWN_IDS.put("leatherboots", 1061);
		KNOWN_IDS.put("airtalisman", 1438);
		KNOWN_IDS.put("ghostspeakamulet", 552);
		KNOWN_IDS.put("chefshat", 1949);
		KNOWN_IDS.put("piedish", 2313);
		// confirmed via wiki; several postdate the bundled offline item list
		KNOWN_IDS.put("crateoflooty", 32808);
		KNOWN_IDS.put("eclipsered", 29415);
		KNOWN_IDS.put("dualmacuahuitl", 28997);
		// elemental/common runes (guide says "Air Runes"; plural handled by lookup)
		KNOWN_IDS.put("airrune", 556);
		KNOWN_IDS.put("waterrune", 555);
		KNOWN_IDS.put("earthrune", 557);
		KNOWN_IDS.put("firerune", 554);
		KNOWN_IDS.put("mindrune", 558);
		KNOWN_IDS.put("bodyrune", 559);
		KNOWN_IDS.put("lawrune", 563);
		KNOWN_IDS.put("naturerune", 561);
		KNOWN_IDS.put("chaosrune", 562);
		KNOWN_IDS.put("cosmicrune", 564);
		// common early/mid-game tools and materials the price search misses
		KNOWN_IDS.put("plank", 960);
		KNOWN_IDS.put("moltenglass", 1775);
		KNOWN_IDS.put("coal", 453);
		KNOWN_IDS.put("ironore", 440);
		KNOWN_IDS.put("silverore", 442);
		KNOWN_IDS.put("bronzebar", 2349);
		KNOWN_IDS.put("ironbar", 2351);
		KNOWN_IDS.put("steelbar", 2353);
		KNOWN_IDS.put("bronzepickaxe", 1265);
		KNOWN_IDS.put("steelpickaxe", 1269);
		KNOWN_IDS.put("bronzeaxe", 1351);
		KNOWN_IDS.put("adamantaxe", 1357);
		KNOWN_IDS.put("hammer", 2347);
		KNOWN_IDS.put("needle", 1733);
		KNOWN_IDS.put("thread", 1734);
		KNOWN_IDS.put("chisel", 1755);
		KNOWN_IDS.put("shears", 1735);
		KNOWN_IDS.put("rope", 954);
		KNOWN_IDS.put("pestlemortar", 233);
		KNOWN_IDS.put("pestleandmortar", 233);
		KNOWN_IDS.put("vialofwater", 227);
		KNOWN_IDS.put("swamptar", 1939);
		KNOWN_IDS.put("bucketofwater", 1929);
		KNOWN_IDS.put("bucketofmilk", 1927);
		KNOWN_IDS.put("jugofwater", 1937);
		KNOWN_IDS.put("potofflour", 1933);
		KNOWN_IDS.put("egg", 1944);
		KNOWN_IDS.put("batbones", 530);
		KNOWN_IDS.put("ballofwool", 1759);
		KNOWN_IDS.put("guam", 249);
		KNOWN_IDS.put("guamleaf", 249);
		KNOWN_IDS.put("dramenstaff", 772);
		KNOWN_IDS.put("wateringcan", 5331);
		KNOWN_IDS.put("seeddibber", 5343);
		KNOWN_IDS.put("candle", 36);
		KNOWN_IDS.put("bootsoflightness", 88);
	}

	private final ItemManager itemManager;
	private final net.runelite.api.Client client;
	private final net.runelite.client.callback.ClientThread clientThread;
	private final Map<String, Integer> cache = new ConcurrentHashMap<>();

	// ------- full-database fallback for names the price search can't find
	// (untradeables: talismans, quest amulets, moulds, keys, ...). One
	// chunked, client-thread scan over all item definitions fills ONLY the
	// names that actually failed - nothing else is indexed or retained.
	/** Normalized names waiting for the scan. */
	private final java.util.Set<String> pendingScan = ConcurrentHashMap.newKeySet();
	/** Names a completed scan could not find either - never rescan for these. */
	private final java.util.Set<String> unresolvable = ConcurrentHashMap.newKeySet();

	/**
	 * Per-word singular form of a pending name, mapped to its normalized key.
	 *
	 * <p>{@link ItemReq#normalize} strips the spaces, so "buckets of sand"
	 * becomes "bucketsofsand" - a single token that no longer singularizes.
	 * Capturing the singular of the ORIGINAL spelling keeps the word boundaries,
	 * which is what lets it match the item "Bucket of sand".</p>
	 */
	private final java.util.Map<String, java.util.Set<String>> pendingSingular =
		new ConcurrentHashMap<>();
	/** Serializes scan ownership changes with reset so an old invocation cannot
	 * reacquire the running flag after a guide switch. */
	private final Object scanStateLock = new Object();
	private boolean scanRunning;
	/** Invalidates client-thread scan chunks when the guide changes or the plugin stops. */
	private final java.util.concurrent.atomic.AtomicInteger scanGeneration =
		new java.util.concurrent.atomic.AtomicInteger();
	/** Small client-thread batches avoid visible frame stalls while walking item definitions. */
	private static final int SCAN_CHUNK = 500;
	/** Defense-in-depth cap for a hostile custom guide within one active model. */
	private static final int MAX_TRACKED_NAMES = 100_000;

	/**
	 * Bumped whenever any name changes resolution state (a new id lands in
	 * the cache, or a completed scan condemns names as unresolvable).
	 * Consumers that cache resolved ids (HUD item strip, managed bank tag)
	 * fold this into their cache keys/signatures, so a late resolution
	 * refreshes them without any explicit invalidation call.
	 */
	private final java.util.concurrent.atomic.AtomicInteger revision =
		new java.util.concurrent.atomic.AtomicInteger();

	/** What the resolver currently knows about a requirement name. */
	public enum ResolutionState
	{
		/** Maps to a real item id (curated, cached, or via alias). */
		RESOLVED,
		/** Unknown so far, but a full-database scan is still owed. */
		PENDING,
		/** A completed full scan covered this name and found nothing: free text. */
		UNRESOLVABLE,
		/**
		 * Names a concept, not an item ("Combat Gear"). Renders as a sprite,
		 * is never inventory-checked, and never blocks trip readiness.
		 */
		CONCEPT
	}

	@Inject
	public ItemIconResolver(ItemManager itemManager, net.runelite.api.Client client,
		net.runelite.client.callback.ClientThread clientThread)
	{
		this.itemManager = itemManager;
		this.client = client;
		this.clientThread = clientThread;
	}

	/**
	 * Sprite ids are encoded as (SPRITE_ID_BASE - spriteId). Far below any real
	 * item id and below -1, so nothing that checks {@code id <= 0} or
	 * {@code id == -1} changes behaviour.
	 */
	private static final int SPRITE_ID_BASE = -1000;

	/** Combat tab attack-style icon (crossed swords). */
	private static final int SPRITE_CROSSED_SWORDS =
		net.runelite.api.SpriteID.TAB_COMBAT;


	/**
	 * True when {@code id} is a sprite-backed concept slot (see
	 * {@link ConceptItems}) rather than a real item.
	 */
	public static boolean isSpriteId(int id)
	{
		return id <= SPRITE_ID_BASE;
	}

	/** Decode a sprite-backed id produced by {@link #resolve}. */
	public static int spriteIdOf(int encoded)
	{
		return SPRITE_ID_BASE - encoded;
	}

	/** Resolve each requirement to an item id (-1 when unknown). Call off the EDT. */
	public int[] resolve(List<ItemReq> reqs)
	{
		int[] ids = new int[reqs.size()];
		for (int i = 0; i < reqs.size(); i++)
		{
			ids[i] = resolveOne(reqs.get(i).getIconName());
		}
		return ids;
	}


	/** Resolve every concrete option represented by one requirement. */
	public int[] resolveAll(ItemReq req)
	{
		java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
		for (String name : req.getMatchNames())
		{
			int id = resolveOne(name);
			if (id > 0)
			{
				ids.add(id);
			}
		}
		int[] out = new int[ids.size()];
		int i = 0;
		for (int id : ids)
		{
			out[i++] = id;
		}
		return out;
	}

	/** Resolution state for an exact or interchangeable requirement. */
	public ResolutionState stateOf(ItemReq req)
	{
		if (req == null)
		{
			return ResolutionState.UNRESOLVABLE;
		}
		if (req.isCategoryRequirement())
		{
			return ResolutionState.RESOLVED;
		}
		if (!req.isAlternativeGroup())
		{
			return stateOf(req.getName());
		}
		boolean pending = false;
		for (String option : req.getAlternatives())
		{
			ResolutionState state = stateOf(option);
			if (state == ResolutionState.RESOLVED)
			{
				return ResolutionState.RESOLVED;
			}
			if (state == ResolutionState.PENDING)
			{
				pending = true;
			}
		}
		return pending ? ResolutionState.PENDING : ResolutionState.UNRESOLVABLE;
	}

	private int resolveOne(String name)
	{
		String key = ItemReq.normalize(name);
		if (ConceptItems.isConcept(name))
		{
			return SPRITE_ID_BASE - SPRITE_CROSSED_SWORDS;
		}
		Integer cached = cache.get(key);
		if (cached != null)
		{
			return cached;
		}

		// colloquial guide names ("Ardy Cloak", "Wine") hop to the exact
		// in-game name first, then resolve through the normal pipeline
		String canonical = ItemAliases.canonical(key);
		String lookupName = canonical != null ? canonical : name;
		String lookupKey = canonical != null ? ItemReq.normalize(canonical) : key;
		if (canonical != null)
		{
			Integer viaCanonical = cache.get(lookupKey);
			if (viaCanonical != null)
			{
				cacheResolved(key, viaCanonical);
				return viaCanonical;
			}
		}

		int id = byKnownId(lookupName);
		if (id <= 0)
		{
			id = bySearch(lookupName);
		}
		if (id <= 0 && lookupName.toLowerCase(Locale.ROOT).endsWith("s"))
		{
			id = bySearch(lookupName.substring(0, lookupName.length() - 1));
		}

		// only cache successes: a failure may just mean the price list hasn't
		// loaded yet, and re-searching on a later rebuild is cheap
		if (id > 0)
		{
			boolean inserted = cacheResolved(key, id);
			inserted |= cacheResolved(lookupKey, id);
			if (inserted)
			{
				revision.incrementAndGet();
			}
		}
		else if (!unresolvable.contains(lookupKey)
			&& (pendingScan.contains(lookupKey) || pendingScan.size() < MAX_TRACKED_NAMES))
		{
			// one-to-many: two requirements can share a singular form, and the
			// first must not permanently win the slot
			for (String candidate : Names.singularCandidates(lookupName))
			{
				pendingSingular.computeIfAbsent(candidate,
					k -> ConcurrentHashMap.newKeySet()).add(lookupKey);
			}
			// remember for the full-database scan (untradeables etc.)
			pendingScan.add(lookupKey);
		}
		return id;
	}

	private boolean cacheResolved(String key, int id)
	{
		if (key == null || key.isEmpty())
		{
			return false;
		}
		Integer existing = cache.get(key);
		if (existing != null)
		{
			return false;
		}
		if (cache.size() >= MAX_TRACKED_NAMES)
		{
			return false;
		}
		return cache.putIfAbsent(key, id) == null;
	}

	/**
	 * Drop all per-guide state. Called on guide replacement and shutdown so the
	 * name caches never outlive the model that supplied them.
	 */
	public void reset()
	{
		// Invalidate first: a chunk already queued on the client thread must not
		// repopulate the just-cleared maps or release a newer scan's running flag.
		synchronized (scanStateLock)
		{
			scanGeneration.incrementAndGet();
			scanRunning = false;
		}
		cache.clear();
		pendingScan.clear();
		unresolvable.clear();
		pendingSingular.clear();
		revision.incrementAndGet();
	}

	/** True when a full-database scan could find more icons. */
	public boolean hasPendingScan()
	{
		return !pendingScan.isEmpty();
	}

	/**
	 * How many names are still waiting on a full scan. Exposed so a preload can
	 * show whether it is making progress: a falling number is a slow scan, a
	 * static or rising one means names are being re-queued as fast as they are
	 * condemned.
	 */
	public int pendingScanCount()
	{
		return pendingScan.size();
	}

	/** Monotonic resolution-state revision - see the field for the contract. */
	public int revision()
	{
		return revision.get();
	}

	/**
	 * The name's current resolution state. Cheap map lookups only - no
	 * searching - so it's safe on the client thread every tick. Trip-ready
	 * uses this three ways: RESOLVED counts against the inventory, PENDING
	 * blocks a "ready" verdict (the truth isn't known yet, so never claim
	 * or sound it), and UNRESOLVABLE is ignored as free text ("2 Food") -
	 * but only ever after a completed full scan actually proved it.
	 */
	public ResolutionState stateOf(String name)
	{
		if (name == null)
		{
			return ResolutionState.UNRESOLVABLE;
		}
		// checked before any id lookup: a concept resolves to a NEGATIVE sprite
		// id, so the "> 0" test below would leave it PENDING forever and stall
		// trip readiness
		if (ConceptItems.isConcept(name))
		{
			return ResolutionState.CONCEPT;
		}
		if (byKnownId(name) > 0)
		{
			return ResolutionState.RESOLVED;
		}
		String key = ItemReq.normalize(name);
		String canonical = ItemAliases.canonical(key);
		String lookupKey = canonical != null ? ItemReq.normalize(canonical) : key;
		if (cache.containsKey(key) || cache.containsKey(lookupKey))
		{
			return ResolutionState.RESOLVED;
		}
		return unresolvable.contains(lookupKey)
			? ResolutionState.UNRESOLVABLE
			: ResolutionState.PENDING;
	}

	/**
	 * Scan every item definition for the pending unresolved names, in
	 * client-thread chunks so no single tick stalls. Finds untradeable items
	 * (talismans, quest amulets, moulds, ...) that the price-based search
	 * can never see. Runs at most once at a time; names that even a full
	 * scan can't match are remembered and never rescanned.
	 *
	 * @param onComplete called on the CLIENT THREAD when anything new resolved
	 */
	public void scanFullDatabase(Runnable onComplete)
	{
		final int generation;
		synchronized (scanStateLock)
		{
			if (pendingScan.isEmpty() || scanRunning)
			{
				return;
			}
			generation = scanGeneration.get();
			scanRunning = true;
		}
		// SNAPSHOT the targets: names added to pendingScan while the scan is
		// already past their id range must NOT be condemned at completion -
		// they stay pending and get their own scan later.
		final java.util.Set<String> targets = ConcurrentHashMap.newKeySet();
		targets.addAll(pendingScan);
		if (targets.isEmpty())
		{
			finishScan(generation);
			return;
		}
		try
		{
			clientThread.invokeLater(() -> scanChunk(0, targets, false, onComplete, generation));
		}
		catch (RuntimeException ex)
		{
			finishScan(generation);
		}
	}

	private void scanChunk(int startId, java.util.Set<String> targets, boolean foundAny,
		Runnable onComplete, int generation)
	{
		if (scanGeneration.get() != generation)
		{
			return;
		}
		int total;
		try
		{
			total = client.getItemCount();
		}
		catch (Exception e)
		{
			finishScan(generation);
			return; // client not ready - a later request retries
		}
		if (total <= 0)
		{
			// cache not loaded yet (login screen) - retry on a later request
			// rather than condemning every name after scanning nothing
			finishScan(generation);
			return;
		}
		int end = Math.min(startId + SCAN_CHUNK, total);
		boolean found = foundAny;
		for (int id = startId; id < end; id++)
		{
			try
			{
				net.runelite.api.ItemComposition c = itemManager.getItemComposition(id);
				if (c == null || c.getNote() != -1 || c.getPlaceholderTemplateId() != -1)
				{
					continue; // noted/placeholder variants never win a slot icon
				}
				String n = c.getName();
				if (n == null || "null".equals(n))
				{
					continue;
				}
				String norm = ItemReq.normalize(n);
				if (targets.remove(norm))
				{
					cacheResolved(norm, id);
					pendingScan.remove(norm);
					found = true;
				}
				// guide says "Air Runes", definition says "Air rune"
				if (targets.remove(norm + "s"))
				{
					cacheResolved(norm + "s", id);
					pendingScan.remove(norm + "s");
					found = true;
				}
				// "buckets of sand" vs "Bucket of sand": the plural sits on a
				// non-final word, so trailing-s handling alone never matches.
				// Names.singularize normalizes every word.
				for (String candidate : Names.singularCandidates(n))
				{
					java.util.Set<String> wantKeys = pendingSingular.get(candidate);
					if (wantKeys == null)
					{
						continue;
					}
					for (String wantKey : new java.util.ArrayList<>(wantKeys))
					{
						if (targets.remove(wantKey))
						{
							cacheResolved(wantKey, id);
							pendingScan.remove(wantKey);
							wantKeys.remove(wantKey);
							found = true;
						}
					}
					if (wantKeys.isEmpty())
					{
						pendingSingular.remove(candidate);
					}
				}
				// guide says "Maple Log", definition says "Maple logs"
				if (norm.endsWith("s"))
				{
					String singular = norm.substring(0, norm.length() - 1);
					if (targets.remove(singular))
					{
						cacheResolved(singular, id);
						pendingScan.remove(singular);
						found = true;
					}
				}
			}
			catch (Exception ignored)
			{
				// one bad definition must not abort the scan
			}
		}
		if (end < total)
		{
			final boolean f = found;
			try
			{
				clientThread.invokeLater(() -> scanChunk(end, targets, f, onComplete, generation));
			}
			catch (RuntimeException ex)
			{
				finishScan(generation);
			}
			return;
		}
		if (scanGeneration.get() != generation)
		{
			return;
		}
		// done: only names this scan ACTUALLY covered and didn't find are
		// declared nonexistent; anything added mid-scan stays pending
		boolean condemnedAny = !targets.isEmpty();
		// Anything still unmatched after a full scan of every item definition is
		// genuinely not an item under this name. Only the count is logged: names
		// may originate in a private user-imported guide.
		// One sweep for the whole batch, not one per miss. The previous form ran
		// inside this loop, giving O(misses x buckets) work on the client thread.
		if (!targets.isEmpty())
		{
			pendingSingular.values().forEach(keys -> keys.removeAll(targets));
			pendingSingular.entrySet().removeIf(e -> e.getValue().isEmpty());
		}
		int newlyUnresolvable = 0;
		for (String miss : targets)
		{
			if (unresolvable.add(miss))
			{
				newlyUnresolvable++;
			}
		}
		if (newlyUnresolvable > 0)
		{
			// Count only: names can originate in a user-imported guide and do
			// not belong in persistent logs, even at debug level.
			log.debug("Guide Overlay: {} item requirement name(s) unresolved after full scan",
				newlyUnresolvable);
		}
		pendingScan.removeAll(targets);
		if (found || condemnedAny)
		{
			// PENDING names moved to RESOLVED or UNRESOLVABLE
			revision.incrementAndGet();
		}
		finishScan(generation);
		if (found && onComplete != null && scanGeneration.get() == generation)
		{
			try
			{
				onComplete.run();
			}
			catch (Exception e)
			{
				// callback failure must not poison the resolver state
			}
		}
	}

	private void finishScan(int generation)
	{
		synchronized (scanStateLock)
		{
			if (scanGeneration.get() == generation)
			{
				scanRunning = false;
			}
		}
	}

	private static int byKnownId(String name)
	{
		String norm = ItemReq.normalize(name);
		Integer id = KNOWN_IDS.get(norm);
		if (id == null && norm.endsWith("s"))
		{
			id = KNOWN_IDS.get(norm.substring(0, norm.length() - 1));
		}
		return id != null ? id : -1;
	}

	private int bySearch(String name)
	{
		try
		{
			List<ItemPrice> results = itemManager.search(name);
			if (results == null || results.isEmpty())
			{
				return -1;
			}
			for (ItemPrice p : results)
			{
				if (ItemReq.namesEquivalent(name, p.getName()))
				{
					return p.getId();
				}
			}
		}
		catch (Exception ignored)
		{
			// search unavailable (e.g. offline) - the grid falls back to text chips
		}
		return -1;
	}
}
