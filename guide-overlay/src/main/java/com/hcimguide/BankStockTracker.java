package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks how many of each teleport-relevant item (runes, jewelry, tablets)
 * the player has - in the inventory, equipped, and in the bank - so the
 * route suggester can offer only teleports the player can actually use.
 *
 * Updates arrive on the client thread from container-change events; the
 * bank column is also persisted to config so suggestions can say "in bank"
 * across sessions (it refreshes the next time the bank opens - a stale
 * count between sessions only affects a suggestion's wording, nothing
 * game-state-critical). Only the ~15 name prefixes from the teleport
 * directory are ever tracked - a bank pass is one name comparison per
 * item per prefix, and nothing about the rest of the bank is recorded.
 */
@Singleton
public class BankStockTracker
{
	private static final Logger log = LoggerFactory.getLogger(BankStockTracker.class);
	private static final String STOCK_KEY = "teleportBankStock";
	private static final Type PERSIST_TYPE = new TypeToken<Map<String, int[]>>()
	{
	}.getType();

	static final int SRC_INVENTORY = 0;
	static final int SRC_EQUIPMENT = 1;
	static final int SRC_BANK = 2;
	private static final int SOURCES = 3;

	private final ItemManager itemManager;
	private final ConfigManager configManager;
	private final Gson gson;

	/**
	 * prefix -> counts[source * 2 + (0=any variant, 1=charged "(n)" variant)].
	 * Guarded by {@code this}; updates are client-thread, reads are cheap.
	 */
	private final Map<String, int[]> counts = new HashMap<>();
	private final Set<String> parts = TeleportDirectory.allNeedParts();

	/** Bumped on every change so cached route suggestions know to recompute. */
	private volatile long revision;

	@Inject
	public BankStockTracker(ItemManager itemManager, ConfigManager configManager, Gson gson)
	{
		this.itemManager = itemManager;
		this.configManager = configManager;
		this.gson = gson;
	}

	/** True once live bank contents have been seen this session. */
	private volatile boolean bankSeenLive;

	/** Recount one container's column. Client thread (item compositions). */
	public synchronized void update(int source, Item[] items)
	{
		if (source < 0 || source >= SOURCES)
		{
			return;
		}
		if (source == SRC_BANK)
		{
			bankSeenLive = true;
		}
		// zero this source's column
		for (int[] row : counts.values())
		{
			row[source * 2] = 0;
			row[source * 2 + 1] = 0;
		}
		if (items != null)
		{
			for (Item item : items)
			{
				if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
				{
					continue;
				}
				String name;
				try
				{
					ItemComposition comp = itemManager.getItemComposition(item.getId());
					name = comp == null ? null : comp.getName();
				}
				catch (Exception e)
				{
					continue; // composition unavailable - skip the item, never throw
				}
				if (name == null || "null".equals(name))
				{
					continue;
				}
				String low = name.toLowerCase(Locale.ROOT);
				for (String part : parts)
				{
					if (low.startsWith(part))
					{
						int[] row = counts.computeIfAbsent(part, k -> new int[SOURCES * 2]);
						row[source * 2] += item.getQuantity();
						if (hasChargeSuffix(low))
						{
							row[source * 2 + 1] += item.getQuantity();
						}
					}
				}
			}
		}
		revision++;
	}

	/** Inventory + equipped count for a requirement. */
	public synchronized int carried(TeleportOption.ItemNeed need)
	{
		return countFor(need, SRC_INVENTORY) + countFor(need, SRC_EQUIPMENT);
	}

	/** Banked count for a requirement. */
	public synchronized int banked(TeleportOption.ItemNeed need)
	{
		return countFor(need, SRC_BANK);
	}

	/**
	 * True only for a NUMERIC charge suffix like "(4)" - cosmetic suffixes
	 * such as "Amulet of glory (t)" (an UNCHARGED trimmed glory) must not
	 * count as charged.
	 */
	static boolean hasChargeSuffix(String lowerName)
	{
		int i = lowerName.lastIndexOf('(');
		return i >= 0 && i + 1 < lowerName.length() && Character.isDigit(lowerName.charAt(i + 1));
	}

	private int countFor(TeleportOption.ItemNeed need, int source)
	{
		int[] row = counts.get(need.getNamePart());
		if (row == null)
		{
			return 0;
		}
		return row[source * 2 + (need.isChargedOnly() ? 1 : 0)];
	}

	public long revision()
	{
		return revision;
	}

	// ---------------------------------------------------------- persistence

	/** Persist the bank column (call off the client thread). */
	public void saveBankColumn()
	{
		Map<String, int[]> bank = new HashMap<>();
		synchronized (this)
		{
			for (Map.Entry<String, int[]> e : counts.entrySet())
			{
				int any = e.getValue()[SRC_BANK * 2];
				int charged = e.getValue()[SRC_BANK * 2 + 1];
				if (any > 0 || charged > 0)
				{
					bank.put(e.getKey(), new int[]{any, charged});
				}
			}
		}
		try
		{
			configManager.setConfiguration(HcimGuideConfig.GROUP, STOCK_KEY, gson.toJson(bank));
		}
		catch (Exception e)
		{
			log.warn("Could not persist teleport bank stock", e);
		}
	}

	/** Load the persisted bank column (any thread; validated and bounded). */
	public void loadBankColumn()
	{
		if (bankSeenLive)
		{
			// live bank contents (e.g. replayed on plugin enable mid-session)
			// beat last session's persisted counts - never overwrite them
			return;
		}
		String json = configManager.getConfiguration(HcimGuideConfig.GROUP, STOCK_KEY);
		if (json == null || json.isEmpty() || json.length() > 8192)
		{
			return; // absent, or implausibly large for ~15 tracked prefixes
		}
		Map<String, int[]> bank;
		try
		{
			bank = gson.fromJson(json, PERSIST_TYPE);
		}
		catch (Exception e)
		{
			log.warn("Could not parse persisted teleport bank stock", e);
			return;
		}
		if (bank == null)
		{
			return;
		}
		synchronized (this)
		{
			if (bankSeenLive)
			{
				return; // a live update won the race while we were parsing
			}
			for (Map.Entry<String, int[]> e : bank.entrySet())
			{
				int[] v = e.getValue();
				// only known prefixes, only sane non-negative counts
				if (e.getKey() != null && parts.contains(e.getKey())
					&& v != null && v.length == 2 && v[0] >= 0 && v[1] >= 0
					&& v[0] <= 1_000_000_000 && v[1] <= 1_000_000_000)
				{
					int[] row = counts.computeIfAbsent(e.getKey(), k -> new int[SOURCES * 2]);
					row[SRC_BANK * 2] = v[0];
					row[SRC_BANK * 2 + 1] = v[1];
				}
			}
		}
		revision++;
	}

	/** Forget everything (plugin shutdown; the persisted bank column remains). */
	public synchronized void reset()
	{
		counts.clear();
		bankSeenLive = false;
		revision++;
	}
}
