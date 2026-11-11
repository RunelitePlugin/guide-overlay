package com.hcimguide;

import com.google.gson.Gson;
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
	/**
	 * Guides removed from the dropdown whose snapshot and progress are still
	 * stored under their id. An id listed here is only ever handed back to the
	 * SAME source (same wiki page, or the same name for a file-only guide);
	 * any other new guide gets a different id, so it can never open on another
	 * page's stored text or inherit its ticks.
	 */
	private static final String REMOVED_KEY = "guidesRemoved";
	private static final int MAX_REGISTRY_CHARS = 64 * 1024;
	private static final int MAX_USER_GUIDES = 64;
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
		// PINNED to a commit SHA for reproducible downloads. A future release
		// must update the reviewed commit explicitly; moving branch names are not
		// accepted here.
		BUILTINS.add(new Entry(BRUHSAILER_ID, "BRUHsailer Ironman Guide", null,
			"https://raw.githubusercontent.com/umkyzn/BRUHsailer/ec20b7e843da2cca69bf2343734dd2c25fef913f/data/guide_data.json"));
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

	/** The registry's outside dependencies, narrowed so it can be exercised without a live client. */
	interface Backing
	{
		String read(String key);

		void write(String key, String value);

		/** True when guide text is stored under this id, whether or not the id is registered. */
		boolean hasSnapshot(String guideId);
	}

	private final Backing backing;
	private final Gson gson;

	@Inject
	public GuideRegistry(ConfigManager configManager, Gson gson, GuideService guideService)
	{
		this(new Backing()
		{
			@Override
			public String read(String key)
			{
				return configManager.getConfiguration(HcimGuideConfig.GROUP, key);
			}

			@Override
			public void write(String key, String value)
			{
				configManager.setConfiguration(HcimGuideConfig.GROUP, key, value);
			}

			@Override
			public boolean hasSnapshot(String guideId)
			{
				return guideService.hasSnapshot(guideId);
			}
		}, gson);
	}

	GuideRegistry(Backing backing, Gson gson)
	{
		this.backing = backing;
		this.gson = gson;
	}

	/** All guides, built-in first. Always returns at least the built-in entry. */
	public synchronized List<Entry> list()
	{
		List<Entry> entries = readUserEntries(REGISTRY_KEY);
		// built-ins first, in declared order
		for (int i = BUILTINS.size() - 1; i >= 0; i--)
		{
			Entry b = BUILTINS.get(i);
			entries.add(0, new Entry(b.id, b.title, b.wikiPage, b.sourceUrl));
		}
		return entries;
	}

	/** The validated user entries stored under one config key (the registry or the removed list). */
	private List<Entry> readUserEntries(String key)
	{
		List<Entry> entries = new ArrayList<>();
		String json = backing.read(key);
		if (json != null && !json.isEmpty() && json.length() <= MAX_REGISTRY_CHARS)
		{
			try
			{
				Entry[] saved = gson.fromJson(json, Entry[].class);
				if (saved != null)
				{
					// keyed by id, FIRST valid entry wins: a tampered registry
					// with duplicate ids must yield one stable dropdown entry,
					// not several that byId()/remove can disagree about
					java.util.Map<String, Entry> byId = new java.util.LinkedHashMap<>();
					for (Entry e : saved)
					{
						if (byId.size() >= MAX_USER_GUIDES)
						{
							break;
						}
						// re-validate ids on READ, not just on creation: ids become
						// file names (guides/<id>.txt) and config-key fragments, so
						// a tampered/corrupted registry must never yield a path like
						// "../../x"
						if (e != null && e.id != null && e.title != null && !e.isBuiltin()
							&& GUIDE_ID.matcher(e.id).matches() && validWikiPage(e.wikiPage))
						{
							e.title = sanitizeTitle(e.title); // defense on read too
							e.sourceUrl = null; // user entries can never carry a raw URL
							byId.putIfAbsent(e.id, e);
						}
					}
					entries.addAll(byId.values());
				}
			}
			catch (Exception e)
			{
				log.warn("Could not parse guide registry", e);
			}
		}
		else if (json != null && json.length() > MAX_REGISTRY_CHARS)
		{
			log.warn("Guide registry exceeds the safety cap and was ignored");
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
	private static boolean validWikiPage(String wikiPage)
	{
		if (wikiPage == null)
		{
			return true; // file-only guide
		}
		return WikiUrl.isValidTitle(wikiPage);
	}

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
		int userCount = 0;
		for (Entry e : entries)
		{
			if (!e.isBuiltin())
			{
				userCount++;
			}
			if (wikiPage != null && wikiPage.equals(e.wikiPage))
			{
				return e;
			}
		}
		if (userCount >= MAX_USER_GUIDES)
		{
			throw new IllegalStateException("At most " + MAX_USER_GUIDES + " user guides can be registered");
		}
		List<Entry> removed = readUserEntries(REMOVED_KEY);
		String id = null;
		for (Entry r : removed)
		{
			// the same guide coming back: its stored text and progress are its own
			if (byIdIn(entries, r.id) == null && (wikiPage != null
				? wikiPage.equals(r.wikiPage) : r.wikiPage == null && title.equals(r.title)))
			{
				id = r.id;
				removed.remove(r);
				backing.write(REMOVED_KEY, gson.toJson(removed));
				break;
			}
		}
		if (id == null)
		{
			// Never an id that still has stored data behind it: not a removed
			// guide's (a DIFFERENT source, or the loop above would have taken
			// it), and not one with a snapshot on disk that no list accounts for.
			String base = slug(title);
			id = base;
			int n = 2;
			while (byIdIn(entries, id) != null || byIdIn(removed, id) != null || backing.hasSnapshot(id))
			{
				id = base + "-" + n++;
			}
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
		Entry gone = byIdIn(entries, id);
		if (gone == null)
		{
			return false;
		}
		entries.remove(gone);
		persist(entries);
		// remember whose data stays stored under this id (newest kept when full)
		List<Entry> removed = readUserEntries(REMOVED_KEY);
		removed.removeIf(e -> e.id.equals(id));
		removed.add(new Entry(gone.id, gone.title, gone.wikiPage));
		while (removed.size() > MAX_USER_GUIDES)
		{
			removed.remove(0);
		}
		backing.write(REMOVED_KEY, gson.toJson(removed));
		return true;
	}

	private void persist(List<Entry> entries)
	{
		// the built-in entry is re-synthesized on read; don't store it
		List<Entry> userEntries = new ArrayList<>();
		for (Entry e : entries)
		{
			if (!e.isBuiltin() && userEntries.size() < MAX_USER_GUIDES)
			{
				userEntries.add(e);
			}
		}
		backing.write(REGISTRY_KEY, gson.toJson(userEntries));
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

	/** Same shape GuideService enforces: ids become file names and config keys. */
	private static final java.util.regex.Pattern GUIDE_ID =
		java.util.regex.Pattern.compile("[a-z0-9-]{1,64}");
	private static final java.util.regex.Pattern NON_SLUG =
		java.util.regex.Pattern.compile("[^a-z0-9]+");
	private static final java.util.regex.Pattern SLUG_EDGE_DASH =
		java.util.regex.Pattern.compile("(^-|-$)");

	private static String slug(String s)
	{
		String slug = NON_SLUG.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("-");
		slug = SLUG_EDGE_DASH.matcher(slug).replaceAll("");
		return slug.isEmpty() ? "guide" : (slug.length() > 60 ? slug.substring(0, 60) : slug);
	}
}
