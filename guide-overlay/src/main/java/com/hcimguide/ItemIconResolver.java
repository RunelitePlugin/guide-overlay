package com.hcimguide;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
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
	private final java.util.concurrent.atomic.AtomicBoolean scanRunning =
		new java.util.concurrent.atomic.AtomicBoolean();
	private static final int SCAN_CHUNK = 2000;

	@Inject
	public ItemIconResolver(ItemManager itemManager, net.runelite.api.Client client,
		net.runelite.client.callback.ClientThread clientThread)
	{
		this.itemManager = itemManager;
		this.client = client;
		this.clientThread = clientThread;
	}

	/** Resolve each requirement to an item id (-1 when unknown). Call off the EDT. */
	public int[] resolve(List<ItemReq> reqs)
	{
		int[] ids = new int[reqs.size()];
		for (int i = 0; i < reqs.size(); i++)
		{
			ids[i] = resolveOne(reqs.get(i).getName());
		}
		return ids;
	}

	private int resolveOne(String name)
	{
		String key = ItemReq.normalize(name);
		Integer cached = cache.get(key);
		if (cached != null)
		{
			return cached;
		}

		int id = byKnownId(name);
		if (id <= 0)
		{
			id = bySearch(name);
		}
		if (id <= 0 && name.toLowerCase(Locale.ROOT).endsWith("s"))
		{
			id = bySearch(name.substring(0, name.length() - 1));
		}

		// only cache successes: a failure may just mean the price list hasn't
		// loaded yet, and re-searching on a later rebuild is cheap
		if (id > 0)
		{
			cache.put(key, id);
		}
		else if (!unresolvable.contains(key))
		{
			// remember for the full-database scan (untradeables etc.)
			pendingScan.add(key);
		}
		return id;
	}

	/** True when a full-database scan could find more icons. */
	public boolean hasPendingScan()
	{
		return !pendingScan.isEmpty();
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
		if (pendingScan.isEmpty() || !scanRunning.compareAndSet(false, true))
		{
			return;
		}
		// SNAPSHOT the targets: names added to pendingScan while the scan is
		// already past their id range must NOT be condemned at completion -
		// they stay pending and get their own scan later
		final java.util.Set<String> targets = ConcurrentHashMap.newKeySet();
		targets.addAll(pendingScan);
		clientThread.invokeLater(() -> scanChunk(0, targets, false, onComplete));
	}

	private void scanChunk(int startId, java.util.Set<String> targets, boolean foundAny, Runnable onComplete)
	{
		int total;
		try
		{
			total = client.getItemCount();
		}
		catch (Exception e)
		{
			scanRunning.set(false);
			return; // client not ready - a later request retries
		}
		if (total <= 0)
		{
			// cache not loaded yet (login screen) - retry on a later request
			// rather than condemning every name after scanning nothing
			scanRunning.set(false);
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
					cache.putIfAbsent(norm, id);
					pendingScan.remove(norm);
					found = true;
				}
				// guide says "Air Runes", definition says "Air rune"
				if (targets.remove(norm + "s"))
				{
					cache.putIfAbsent(norm + "s", id);
					pendingScan.remove(norm + "s");
					found = true;
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
			clientThread.invokeLater(() -> scanChunk(end, targets, f, onComplete));
			return;
		}
		// done: only names this scan ACTUALLY covered and didn't find are
		// declared nonexistent; anything added mid-scan stays pending
		unresolvable.addAll(targets);
		pendingScan.removeAll(targets);
		scanRunning.set(false);
		if (found && onComplete != null)
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
