package com.hcimguide;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GuideRegistryTest
{
	private Map<String, String> config;
	/** Ids with guide text on disk, registered or not. */
	private Set<String> snapshots;
	private GuideRegistry registry;

	@Before
	public void setUp()
	{
		config = new HashMap<>();
		snapshots = new HashSet<>();
		registry = newRegistry();
	}

	/** A fresh instance over the same stored state - what the next client start sees. */
	private GuideRegistry newRegistry()
	{
		return new GuideRegistry(new GuideRegistry.Backing()
		{
			@Override
			public String read(String key)
			{
				return config.get(key);
			}

			@Override
			public void write(String key, String value)
			{
				config.put(key, value);
			}

			@Override
			public boolean hasSnapshot(String guideId)
			{
				return snapshots.contains(guideId);
			}
		}, new Gson());
	}

	private GuideRegistry.Entry addFromLink(String link)
	{
		String page = WikiUrl.pageTitle(link);
		return registry.add(WikiUrl.displayName(page), page);
	}

	@Test
	public void builtinsAreAlwaysListedFirst()
	{
		assertEquals(GuideRegistry.BUILTIN_ID, registry.list().get(0).getId());
		assertEquals(GuideRegistry.BRUHSAILER_ID, registry.list().get(1).getId());
		assertFalse(registry.remove(GuideRegistry.BUILTIN_ID));
	}

	@Test
	public void registryWrittenByEarlierVersionsStillLoads()
	{
		config.put("guides", "[{\"id\":\"some-guide\",\"title\":\"Some Guide\",\"wikiPage\":\"Guide:Some_Guide\"},"
			+ "{\"id\":\"from-file\",\"title\":\"From file\"}]");
		assertEquals("Guide:Some_Guide", registry.byId("some-guide").getWikiPage());
		assertNull(registry.byId("from-file").getWikiPage());
	}

	@Test
	public void aTitleContainingPercentStaysListedAfterItIsAdded()
	{
		GuideRegistry.Entry literal = addFromLink("https://oldschool.runescape.wiki/w/Guide:100%25_run");
		GuideRegistry.Entry encodedLooking = addFromLink("https://oldschool.runescape.wiki/w/Guide:A%2541");

		GuideRegistry reloaded = newRegistry();
		assertNotNull("entry vanished on read", reloaded.byId(literal.getId()));
		assertEquals("Guide:100%_run", reloaded.byId(literal.getId()).getWikiPage());
		assertEquals("stored title changed on read",
			"Guide:A%41", reloaded.byId(encodedLooking.getId()).getWikiPage());
	}

	@Test
	public void tamperedEntriesAreStillRejectedOnRead()
	{
		config.put("guides", "[{\"id\":\"../../x\",\"title\":\"t\",\"wikiPage\":\"A\"},"
			+ "{\"id\":\"ok\",\"title\":\"<html>t\",\"wikiPage\":\"A\",\"sourceUrl\":\"https://evil.example/x\"},"
			+ "{\"id\":\"bad-page\",\"title\":\"t\",\"wikiPage\":\"<html>\"},"
			+ "{\"id\":\"spaced\",\"title\":\"t\",\"wikiPage\":\"Has space\"}]");
		assertNull(registry.byId("../../x"));
		assertNull(registry.byId("bad-page"));
		assertNull(registry.byId("spaced"));
		assertEquals("htmlt", registry.byId("ok").getTitle());
		assertNull(registry.byId("ok").getSourceUrl());
	}

	@Test
	public void aRemovedGuidesIdIsNeverGivenToADifferentPage()
	{
		GuideRegistry.Entry first = registry.add("Foo", "Guide:Foo");
		assertEquals("foo", first.getId());
		snapshots.add("foo"); // imported, ticked, then removed: its data stays stored
		assertTrue(registry.remove("foo"));

		GuideRegistry.Entry other = newRegistry().add("Foo", "User:Someone/Foo");
		assertNotEquals("a different page must not open on foo's stored text and progress",
			"foo", other.getId());
		assertFalse(snapshots.contains(other.getId()));
	}

	@Test
	public void theSamePageComingBackGetsItsOwnDataBack()
	{
		registry.add("Foo", "Guide:Foo");
		snapshots.add("foo");
		registry.remove("foo");
		registry.add("Foo", "User:Someone/Foo"); // takes foo-2 meanwhile

		GuideRegistry.Entry back = newRegistry().add("Foo", "Guide:Foo");
		assertEquals("foo", back.getId());

		// and once it is back, removing and replacing it is protected again
		registry = newRegistry();
		registry.remove("foo");
		assertNotEquals("foo", registry.add("Foo", "Foo").getId());
	}

	@Test
	public void aFileOnlyGuideIsMatchedByItsNameAndNeverByAWikiGuide()
	{
		GuideRegistry.Entry file = registry.add("My route", null);
		snapshots.add(file.getId());
		registry.remove(file.getId());

		assertNotEquals(file.getId(), registry.add("My route", "Guide:My_route").getId());
		assertEquals(file.getId(), registry.add("My route", null).getId());
	}

	@Test
	public void anIdWithStoredTextNobodyAccountsForIsSkipped()
	{
		// removed by a version that kept no record of it
		snapshots.add("foo");
		snapshots.add("foo-2");
		assertEquals("foo-3", registry.add("Foo", "Guide:Foo").getId());
	}

	@Test
	public void removedListStaysBounded()
	{
		for (int i = 0; i < 80; i++)
		{
			GuideRegistry.Entry e = registry.add("Guide " + i, "Guide:G" + i);
			registry.remove(e.getId());
		}
		GuideRegistry.Entry[] removed = new Gson().fromJson(config.get("guidesRemoved"), GuideRegistry.Entry[].class);
		assertEquals(64, removed.length);
		assertEquals("guide-79", removed[63].getId());
	}
}
