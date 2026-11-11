package com.hcimguide;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Blob handling of the custom-pin store against an in-memory backing, so what
 * is read back from (possibly damaged or hand-edited) config can be pinned
 * down without a live client.
 */
public class CustomLocationStoreTest
{
	private static final String PINS = "customLocationsV1";
	private static final String BACKUP = "customLocationsV1Backup";
	private static final String INDEXES = "waypointIndexesV1";
	private static final String GUIDE = "b0aty-hcim-v3";
	private static final String STEP = "E1.B1#1a2b#0";

	private Map<String, String> config;
	private CustomLocationStore store;

	@Before
	public void setUp()
	{
		config = new HashMap<>();
		store = new CustomLocationStore(new Gson(), new CustomLocationStore.Storage()
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
		});
	}

	private static String waypoint(String label, int x)
	{
		return "{\"id\":\"manual:x\",\"label\":\"" + label + "\",\"x\":" + x + ",\"y\":3200,\"plane\":0,\"radius\":5}";
	}

	private static String repeat(char c, int n)
	{
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++)
		{
			sb.append(c);
		}
		return sb.toString();
	}

	@Test
	public void storedBlobFromEarlierVersionsStillLoads()
	{
		config.put(PINS, "{\"schemaVersion\":1,\"guides\":{\"" + GUIDE + "\":{\"" + STEP
			+ "\":{\"waypoints\":[" + waypoint("Lumbridge", 3222) + "," + waypoint("Draynor", 3093) + "]}}}}");
		StepLocationPlan plan = store.getPlan(GUIDE, STEP);
		assertNotNull(plan);
		assertEquals(2, plan.size());
		assertEquals("Lumbridge", plan.get(0).getLabel());
		assertEquals(new WorldPoint(3093, 3200, 0), plan.get(1).getPoint());
		assertEquals(CustomLocationStore.StoreState.VALID, store.getState());
	}

	@Test
	public void nullWaypointsStepsAndGuidesInStoredJsonAreDroppedNotDereferenced()
	{
		config.put(PINS, "{\"schemaVersion\":1,\"guides\":{\"other\":null,\"" + GUIDE + "\":{"
			+ "\"gone\":null,"
			+ "\"onlyNull\":{\"waypoints\":[null]},"
			+ "\"" + STEP + "\":{\"waypoints\":[null," + waypoint("Kept", 3222) + ",null]}}}}");

		assertNull(store.getPlan(GUIDE, "gone"));
		assertNull(store.getPlan(GUIDE, "onlyNull"));
		assertFalse(store.hasPlan(GUIDE, "onlyNull"));
		Map<String, StepLocationPlan> plans = store.getPlansForGuide(GUIDE);
		assertEquals(Collections.singleton(STEP), plans.keySet());
		assertEquals(1, plans.get(STEP).size());

		// index 0 is the one real waypoint for every operation, not the null
		store.rename(GUIDE, STEP, 0, "Renamed");
		assertEquals("Renamed", store.getPlan(GUIDE, STEP).get(0).getLabel());
		assertEquals(0, store.removeWaypoint(GUIDE, STEP, 0));
		assertNull(store.getPlan(GUIDE, STEP));
	}

	@Test
	public void oneBadIndexValueDoesNotDiscardTheOthers()
	{
		config.put(INDEXES, "{\"" + GUIDE + "|a\":2,\"" + GUIDE + "|b\":\"x\",\"" + GUIDE
			+ "|c\":{\"deep\":[1]},\"" + GUIDE + "|d\":null,\"" + GUIDE + "|e\":3.0,\"" + GUIDE + "|f\":-4}");
		assertEquals(2, store.getActiveIndex(GUIDE, "a"));
		assertEquals(0, store.getActiveIndex(GUIDE, "b"));
		assertEquals(3, store.getActiveIndex(GUIDE, "e"));
		assertEquals(0, store.getActiveIndex(GUIDE, "f"));

		// and a write made afterwards keeps the readable ones
		store.setActiveIndex(GUIDE, "g", 5);
		assertEquals(2, store.getActiveIndex(GUIDE, "a"));
		assertEquals(3, store.getActiveIndex(GUIDE, "e"));
		assertEquals(5, store.getActiveIndex(GUIDE, "g"));
	}

	@Test
	public void indexBlobNeverGrowsPastTheStorageLimit()
	{
		store.setActiveIndex(GUIDE, "small", 2);
		String before = config.get(INDEXES);
		// one entry whose key alone is larger than the limit
		store.setActiveIndex(GUIDE, repeat('k', 200_000), 3);
		assertEquals("an over-limit blob must not replace the readable one", before, config.get(INDEXES));
		assertEquals(2, store.getActiveIndex(GUIDE, "small"));

		// shrinking stays possible
		store.setActiveIndex(GUIDE, "small", 0);
		assertEquals(0, store.getActiveIndex(GUIDE, "small"));
		assertEquals("{}", config.get(INDEXES));
	}

	@Test
	public void backupWriteIsHeldToTheSameLimitAsThePrimary()
	{
		// a legacy store, valid under the old 1 MB ceiling
		StringBuilder legacy = new StringBuilder("{\"schemaVersion\":1,\"guides\":{\"" + GUIDE + "\":{");
		for (int i = 0; i < 1500; i++)
		{
			legacy.append(i == 0 ? "" : ",").append("\"E1.B1#").append(i).append("#0\":{\"waypoints\":[")
				.append(waypoint("Stop", 3000 + i)).append("]}");
		}
		legacy.append("}}}");
		assertTrue(legacy.length() > 128 * 1024);
		config.put(PINS, legacy.toString());
		config.put(BACKUP, "{\"schemaVersion\":1,\"guides\":{}}");

		store.clear(GUIDE, "E1.B1#7#0"); // shrinking a legacy store is always allowed

		assertTrue(config.get(PINS).length() < legacy.length());
		assertEquals("the over-limit previous value must not be copied into the backup",
			"{\"schemaVersion\":1,\"guides\":{}}", config.get(BACKUP));
		assertNotNull(store.getPlan(GUIDE, "E1.B1#8#0"));
	}

	@Test
	public void ordinaryWritesStillKeepThePreviousValueAsBackup()
	{
		store.replace(GUIDE, STEP, "First", new WorldPoint(3222, 3218, 0));
		String first = config.get(PINS);
		store.replace(GUIDE, STEP, "Second", new WorldPoint(3093, 3243, 0));
		assertEquals(first, config.get(BACKUP));
	}

	@Test
	public void importedLabelsLoseAngleBrackets()
	{
		String shared = "{\"schemaVersion\":1,\"guides\":{\"" + GUIDE + "\":{\"" + STEP + "\":{\"waypoints\":["
			+ "{\"label\":\"<html><img src='https://example.invalid/x.png'>Bank\",\"x\":3222,\"y\":3218,\"plane\":0,\"radius\":5},"
			+ "null]}}}}";
		assertEquals(1, store.importJson(shared, GUIDE, Collections.singleton(STEP)));
		String label = store.getPlan(GUIDE, STEP).get(0).getLabel();
		assertFalse(label, label.contains("<") || label.contains(">"));
		assertTrue(label, label.endsWith("Bank"));
		assertFalse(config.get(PINS).contains("<"));
	}

	@Test
	public void unreadableStoreIsStillLeftUntouched()
	{
		config.put(PINS, "{\"schemaVersion\":1,\"guides\":{\"" + GUIDE + "\":{\"" + STEP + "\":{\"waypoints\":[{\"x\":\"oops\"");
		assertNull(store.getPlan(GUIDE, STEP));
		assertTrue(store.isDamaged());
		try
		{
			store.replace(GUIDE, STEP, "New", new WorldPoint(3222, 3218, 0));
			org.junit.Assert.fail("a damaged store must refuse writes");
		}
		catch (IllegalStateException expected)
		{
			// the stored text is what it was
		}
		assertTrue(config.get(PINS).endsWith("\"oops\""));
	}
}
