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
	}

	private final ItemManager itemManager;
	private final Map<String, Integer> cache = new ConcurrentHashMap<>();

	@Inject
	public ItemIconResolver(ItemManager itemManager)
	{
		this.itemManager = itemManager;
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
		return id;
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
