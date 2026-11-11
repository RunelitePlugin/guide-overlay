package com.hcimguide;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NpcLocationStoreTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private File file;

	@Before
	public void setUp() throws Exception
	{
		file = new File(temp.newFolder("hcim-guide"), "npc-locations.json");
	}

	private NpcLocationStore open()
	{
		NpcLocationStore store = new NpcLocationStore(new Gson(), file);
		store.load();
		return store;
	}

	/** More bulk entries than a load keeps, with keys already normalized. */
	private static Map<String, int[]> bulk(int count)
	{
		Map<String, int[]> out = new HashMap<>();
		for (int i = 0; i < count; i++)
		{
			out.put("zzbulk" + i, new int[]{1000 + (i % 5000), 2000 + (i % 5000), 0});
		}
		return out;
	}

	@Test
	public void storeFileWrittenByEarlierVersionsStillLoads() throws Exception
	{
		Files.write(file.toPath(),
			"{\"auditonlynpc\":[3221,3218,0],\"broken\":[1,2],\"offmap\":[99999,1,0]}".getBytes(StandardCharsets.UTF_8));
		NpcLocationStore store = open();
		assertEquals(new WorldPoint(3221, 3218, 0), store.lookup("Audit-only NPC"));
		assertEquals(null, store.lookup("offmap"));
	}

	@Test
	public void observedPositionsSurviveAStoreLargerThanTheLoadLimit()
	{
		NpcLocationStore first = open();
		for (int i = 0; i < 300; i++)
		{
			first.learn("Observed npc " + i, new WorldPoint(3000 + i, 3100 + i, 1));
		}
		// a bulk dataset arriving AFTER the observations, pushing the store
		// well past what one load reads back
		assertEquals(60_000, first.addNormalizedIfAbsent(bulk(60_000)));
		for (int i = 300; i < 400; i++)
		{
			first.learn("Observed npc " + i, new WorldPoint(3000 + i, 3100 + i, 1));
		}
		first.saveIfDirty();

		NpcLocationStore second = open();
		for (int i = 0; i < 400; i++)
		{
			assertEquals("observed entry " + i + " was cut by the load limit",
				new WorldPoint(3000 + i, 3100 + i, 1), second.lookup("Observed npc " + i));
		}

		// and the order is kept through further save/load cycles
		second.learn("Later npc", new WorldPoint(3200, 3200, 0));
		second.saveIfDirty();
		NpcLocationStore third = open();
		assertEquals(new WorldPoint(3200, 3200, 0), third.lookup("Later npc"));
		for (int i = 0; i < 400; i += 37)
		{
			assertEquals(new WorldPoint(3000 + i, 3100 + i, 1), third.lookup("Observed npc " + i));
		}
	}

	@Test
	public void importedPositionsAreKeptAheadOfBulkDataToo()
	{
		NpcLocationStore first = open();
		first.addNormalizedIfAbsent(bulk(60_000));
		assertEquals(1, first.importJson("{\"Shared by a friend\":[3111,3222,0]}"));
		first.saveIfDirty();
		assertEquals(new WorldPoint(3111, 3222, 0), open().lookup("Shared by a friend"));
	}

	@Test
	public void aSaveRemovesItsOwnLongAbandonedTempFiles() throws Exception
	{
		File stale = new File(file.getParentFile(), "npc-locations.json5550123.tmp");
		File fresh = new File(file.getParentFile(), "npc-locations.json77.tmp");
		File foreign = new File(file.getParentFile(), "something-else.tmp");
		for (File f : new File[]{stale, fresh, foreign})
		{
			Files.write(f.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
		}
		long old = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);
		assertTrue(stale.setLastModified(old));
		assertTrue(foreign.setLastModified(old));

		NpcLocationStore store = open();
		store.learn("Temp sweep npc", new WorldPoint(3221, 3218, 0));
		store.saveIfDirty();

		assertTrue(file.isFile());
		assertFalse(stale.exists());
		assertTrue(fresh.exists());
		assertTrue(foreign.exists());
	}
}
