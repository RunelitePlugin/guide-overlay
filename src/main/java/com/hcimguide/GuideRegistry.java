package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The catalog of guides the dropdown offers: one built-in entry plus any the
 * user added from a wiki link. Stored as JSON in RuneLite config; guide text
 * snapshots themselves live on disk (see {@link GuideService}).
 */
@Singleton
public class GuideRegistry
{
	static final String BUILTIN_ID = "b0aty-hcim-v3";
	static final String BRUHSAILER_ID = "bruhsailer";
	private static final String REGISTRY_KEY = "guides";
	private static final Type ENTRY_LIST = new TypeToken<List<Entry>>()
	{
	}.getType();
	private static final Logger log = LoggerFactory.getLogger(GuideRegistry.class);

	/**
	 * Built-in, one-click-downloadable guides. sourceUrl guides fetch a raw
	 * file directly; wikiPage guides fetch via the wiki API. Only built-ins
	 * may carry a sourceUrl (a hardcoded, trusted host) - user-added guides
	 * are wiki-link or file only, so no arbitrary URL fetch is ever possible.
	 */
	private static final List<Entry> BUILTINS = new ArrayList<>();

	static
	{
		BUILTINS.add(new Entry(BUILTIN_ID, "B0aty HCIM Guide V3", "Guide:B0aty_HCIM_Guide_V3", null));
		// Before a Plugin Hub submission, pin "main" to a commit SHA so the
		// download is reproducible (tools/submit.sh does this automatically):
		//   git ls-remote https://github.com/umkyzn/BRUHsailer HEAD
		BUILTINS.add(new Entry(BRUHSAILER_ID, "BRUHsailer Ironman Guide", null,
			"https://raw.githubusercontent.com/umkyzn/BRUHsailer/main/data/guide_data.json"));
	}

	/** One selectable guide. wikiPage/sourceUrl are null for file-only guides. */
	public static class Entry
	{
		String id;
		String title;
		String wikiPage;
		String sourceUrl;

		Entry()
		{
		}

		Entry(String id, String title, String wikiPage)
		{
			this(id, title, wikiPage, null);
		}

		Entry(String id, String title, String wikiPage, String sourceUrl)
		{
			this.id = id;
			this.title = title;
			this.wikiPage = wikiPage;
			this.sourceUrl = sourceUrl;
		}

		public String getId()
		{
			return id;
		}

		public String getTitle()
		{
			return title;
		}

		public String getWikiPage()
		{
			return wikiPage;
		}

		/** Direct download URL for built-in non-wiki guides, else null. */
		public String getSourceUrl()
		{
			return isBuiltin() ? sourceUrlForBuiltin(id) : null;
		}

		/** True when this guide can be downloaded (wiki page or built-in source URL). */
		public boolean isDownloadable()
		{
			return wikiPage != null || getSourceUrl() != null;
		}

		public boolean isBuiltin()
		{
			return builtinById(id) != null;
		}
	}

	private static Entry builtinById(String id)
	{
		for (Entry e : BUILTINS)
		{
			if (e.id.equals(id))
			{
				return e;
			}
		}
		return null;
	}

	private static String sourceUrlForBuiltin(String id)
	{
		Entry b = builtinById(id);
		return b == null ? null : b.sourceUrl;
	}

	private final ConfigManager configManager;
	private final Gson gson;

	@Inject
	public GuideRegistry(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/** All guides, built-in first. Always returns at least the built-in entry. */
	public synchronized List<Entry> list()
	{
		List<Entry> entries = new ArrayList<>();
		String json = configManager.getConfiguration(HcimGuideConfig.GROUP, REGISTRY_KEY);
		if (json != null && !json.isEmpty())
		{
			try
			{
				List<Entry> saved = gson.fromJson(json, ENTRY_LIST);
				if (saved != null)
				{
					for (Entry e : saved)
					{
						// re-validate ids on READ, not just on creation: ids become
						// file names (guides/<id>.txt) and config-key fragments, so
						// a tampered/corrupted registry must never yield a path like
						// "../../x"
						if (e != null && e.id != null && e.title != null && !e.isBuiltin()
							&& e.id.matches("[a-z0-9-]{1,64}"))
						{
							e.title = sanitizeTitle(e.title); // defense on read too
							e.sourceUrl = null; // user entries can never carry a raw URL
							entries.add(e);
						}
					}
				}
			}
			catch (Exception e)
			{
				log.warn("Could not parse guide registry", e);
			}
		}
		// built-ins first, in declared order
		for (int i = BUILTINS.size() - 1; i >= 0; i--)
		{
			Entry b = BUILTINS.get(i);
			entries.add(0, new Entry(b.id, b.title, b.wikiPage, b.sourceUrl));
		}
		return entries;
	}

	public synchronized Entry byId(String id)
	{
		for (Entry e : list())
		{
			if (e.id.equals(id))
			{
				return e;
			}
		}
		return null;
	}

	/**
	 * Titles render in Swing labels, where text starting with "&lt;html&gt;"
	 * becomes live HTML - never let angle brackets through, at creation OR read.
	 */
	private static String sanitizeTitle(String title)
	{
		String t = title == null ? "" : title.replace("<", "").replace(">", "").trim();
		if (t.length() > 80)
		{
			t = t.substring(0, 80);
		}
		return t.isEmpty() ? "Unnamed guide" : t;
	}

	/** Adds a guide (or returns the existing entry for the same wiki page). */
	public synchronized Entry add(String title, String wikiPage)
	{
		title = sanitizeTitle(title);
		List<Entry> entries = list();
		for (Entry e : entries)
		{
			if (wikiPage != null && wikiPage.equals(e.wikiPage))
			{
				return e;
			}
		}
		String base = slug(title);
		String id = base;
		int n = 2;
		while (byIdIn(entries, id) != null)
		{
			id = base + "-" + n++;
		}
		Entry entry = new Entry(id, title, wikiPage);
		entries.add(entry);
		persist(entries);
		return entry;
	}

	/** Removes a user-added guide. Built-in guides cannot be removed. */
	public synchronized boolean remove(String id)
	{
		if (builtinById(id) != null)
		{
			return false;
		}
		List<Entry> entries = list();
		boolean removed = entries.removeIf(e -> e.id.equals(id));
		if (removed)
		{
			persist(entries);
		}
		return removed;
	}

	private void persist(List<Entry> entries)
	{
		// the built-in entry is re-synthesized on read; don't store it
		List<Entry> userEntries = new ArrayList<>();
		for (Entry e : entries)
		{
			if (!e.isBuiltin())
			{
				userEntries.add(e);
			}
		}
		configManager.setConfiguration(HcimGuideConfig.GROUP, REGISTRY_KEY, gson.toJson(userEntries));
	}

	private static Entry byIdIn(List<Entry> entries, String id)
	{
		for (Entry e : entries)
		{
			if (e.id.equals(id))
			{
				return e;
			}
		}
		return null;
	}

	private static String slug(String s)
	{
		String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
		return slug.isEmpty() ? "guide" : (slug.length() > 60 ? slug.substring(0, 60) : slug);
	}
}
