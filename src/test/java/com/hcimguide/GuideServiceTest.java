package com.hcimguide;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Snapshot storage in a throwaway directory, and the network callbacks against
 * canned responses - no request ever leaves the process.
 */
public class GuideServiceTest
{
	private static final String ID = "test-guide";
	private static final String V1 = "== Episode 1 ==\n'''Bank 1'''\n* Talk to Hans\n";
	private static final String V2 = "== Episode 1 ==\n'''Bank 1'''\n* Talk to Hans\n* Buy a spade\n";
	private static final String V3 = "== Episode 1 ==\n'''Bank 1'''\n* Something else entirely\n";

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private File dir;
	private GuideService service;

	@Before
	public void setUp() throws Exception
	{
		dir = temp.newFolder("guides");
		service = new GuideService(new OkHttpClient(), new Gson(), dir, new WikitextParser());
	}

	private String stored(String name) throws Exception
	{
		return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
	}

	/** A client whose every call is answered locally with the given response shape. */
	private static OkHttpClient answering(Interceptor interceptor)
	{
		return new OkHttpClient.Builder().addInterceptor(interceptor).build();
	}

	private static Response.Builder ok(Request request, String body)
	{
		return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
			.body(ResponseBody.create(MediaType.parse("application/json"), body));
	}

	private static String wikiJson(String wikitext)
	{
		com.google.gson.JsonObject parse = new com.google.gson.JsonObject();
		parse.addProperty("wikitext", wikitext);
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("parse", parse);
		return root.toString();
	}

	/** Runs one fetch to its single callback: {guide-or-null, error-or-null}. */
	private static Object[] fetchOnce(GuideService target) throws Exception
	{
		CountDownLatch settled = new CountDownLatch(1);
		AtomicReference<Guide> guide = new AtomicReference<>();
		AtomicReference<String> error = new AtomicReference<>();
		target.fetch(ID, "Guide:Test", () -> true,
			(g, warning) ->
			{
				guide.set(g);
				settled.countDown();
			},
			message ->
			{
				error.set(message);
				settled.countDown();
			});
		assertTrue("the import never reported an outcome", settled.await(10, TimeUnit.SECONDS));
		return new Object[]{guide.get(), error.get()};
	}

	// ------------------------------------------------------------ storage

	@Test
	public void snapshotLaidDownByEarlierVersionsStillLoads() throws Exception
	{
		Files.write(new File(dir, ID + ".txt").toPath(), V1.getBytes(StandardCharsets.UTF_8));
		assertTrue(service.hasSnapshot(ID));
		assertEquals(1, service.loadStored(ID).totalSteps());
	}

	@Test
	public void importRotatesThePreviousSnapshotIntoTheBackup() throws Exception
	{
		service.importText(ID, V1);
		service.importText(ID, V2);
		assertEquals(V2, stored(ID + ".txt"));
		assertEquals(V1, stored(ID + ".bak"));
	}

	@Test
	public void importingIdenticalTextAgainKeepsTheRealPreviousSnapshot() throws Exception
	{
		service.importText(ID, V1);
		service.importText(ID, V2);
		service.importText(ID, V2);
		service.importText(ID, V2);
		assertEquals(V2, stored(ID + ".txt"));
		assertEquals("the only restorable copy was overwritten by a duplicate", V1, stored(ID + ".bak"));

		// and it is still what a restore brings back
		assertNotNull(service.restorePrevious(ID));
		assertEquals(V1, stored(ID + ".txt"));
		assertEquals(V2, stored(ID + ".bak"));
	}

	@Test
	public void twoImportsFinishingTogetherLeaveAConsistentPair() throws Exception
	{
		service.importText(ID, V1);
		for (int round = 0; round < 20; round++)
		{
			String next = round % 2 == 0 ? V2 : V3;
			String before = stored(ID + ".txt");
			CountDownLatch go = new CountDownLatch(1);
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread[] racers = new Thread[4];
			for (int i = 0; i < racers.length; i++)
			{
				racers[i] = new Thread(() ->
				{
					try
					{
						go.await();
						service.importText(ID, next);
					}
					catch (Throwable t)
					{
						failure.set(t);
					}
				});
				racers[i].start();
			}
			go.countDown();
			for (Thread racer : racers)
			{
				racer.join(10_000);
			}
			assertNull(failure.get());
			assertEquals(next, stored(ID + ".txt"));
			assertEquals("round " + round, before, stored(ID + ".bak"));
		}
		String[] leftovers = dir.list((d, name) -> name.endsWith(".tmp"));
		assertEquals(0, leftovers.length);
	}

	@Test
	public void aSaveRemovesOnlyItsOwnLongAbandonedTempFiles() throws Exception
	{
		long old = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);
		File staleSnapshot = new File(dir, "other-guide.txt4821690347.tmp");
		File staleBackup = new File(dir, ID + ".bak-77123.tmp");
		File fresh = new File(dir, "other-guide.txt999.tmp"); // another client mid-save
		File foreignOld = new File(dir, "notes.tmp");
		File realSnapshot = new File(dir, "other-guide.txt");
		for (File f : new File[]{staleSnapshot, staleBackup, fresh, foreignOld, realSnapshot})
		{
			Files.write(f.toPath(), V1.getBytes(StandardCharsets.UTF_8));
		}
		for (File f : new File[]{staleSnapshot, staleBackup, foreignOld, realSnapshot})
		{
			assertTrue(f.setLastModified(old));
		}

		service.importText(ID, V1);

		assertFalse(staleSnapshot.exists());
		assertFalse(staleBackup.exists());
		assertTrue("a temp young enough to be in use must be left alone", fresh.exists());
		assertTrue("not this service's file", foreignOld.exists());
		assertTrue(realSnapshot.exists());
	}

	@Test
	public void guideIdsThatAreNotPlainSlugsNeverReachTheDisk() throws Exception
	{
		for (String bad : new String[]{"../x", "a/b", "A", "", "a.b", null})
		{
			try
			{
				service.importText(bad, V1);
				fail("accepted id: " + bad);
			}
			catch (IllegalArgumentException expected)
			{
				// nothing written
			}
		}
		assertEquals(0, dir.list().length);
	}

	// ------------------------------------------------- parse runs out of memory/stack

	private GuideService withParserThatThrows(OkHttpClient client, Error error)
	{
		return new GuideService(client, new Gson(), dir, new WikitextParser()
		{
			@Override
			public Guide parse(String wikitext)
			{
				throw error;
			}
		});
	}

	@Test
	public void fileImportReportsFailureWhenParsingExhaustsTheStack() throws Exception
	{
		GuideService failing = withParserThatThrows(new OkHttpClient(), new StackOverflowError());
		try
		{
			failing.importText(ID, V1);
			fail("the import must fail");
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("too large or too deeply nested"));
		}
		assertFalse("nothing of a failed parse may be stored", failing.hasSnapshot(ID));
	}

	@Test
	public void storedGuideThatCannotBeParsedLoadsAsAbsentInsteadOfThrowing() throws Exception
	{
		service.importText(ID, V1);
		GuideService failing = withParserThatThrows(new OkHttpClient(), new OutOfMemoryError("Java heap space"));
		assertNull(failing.loadStored(ID));
		assertNull(failing.restorePrevious(ID));
		assertEquals(V1, stored(ID + ".txt"));
	}

	@Test
	public void wikiImportAlwaysSettlesWhenParsingExhaustsMemoryOrStack() throws Exception
	{
		for (Error error : new Error[]{new OutOfMemoryError("Java heap space"), new StackOverflowError()})
		{
			GuideService failing = withParserThatThrows(
				answering(chain -> ok(chain.request(), wikiJson(V1)).build()), error);
			Object[] outcome = fetchOnce(failing);
			assertNull(outcome[0]);
			assertNotNull("onError must fire", outcome[1]);
			assertTrue((String) outcome[1], ((String) outcome[1]).contains("too large or too deeply nested"));
			assertFalse(failing.hasSnapshot(ID));
		}
	}

	// ------------------------------------------------------------ network

	@Test
	public void wikiImportStoresTheFetchedText() throws Exception
	{
		AtomicReference<Request> seen = new AtomicReference<>();
		GuideService fetching = new GuideService(answering(chain ->
		{
			seen.set(chain.request());
			return ok(chain.request(), wikiJson(V1)).build();
		}), new Gson(), dir, new WikitextParser());

		Object[] outcome = fetchOnce(fetching);
		assertNull((String) outcome[1], outcome[1]);
		assertEquals(1, ((Guide) outcome[0]).totalSteps());
		assertEquals(V1, stored(ID + ".txt"));
		assertEquals("GET", seen.get().method());
		assertNull(seen.get().body());
		assertEquals("oldschool.runescape.wiki", seen.get().url().host());
		assertTrue(seen.get().url().isHttps());
		assertEquals("Guide:Test", seen.get().url().queryParameter("page"));
		assertEquals("parse", seen.get().url().queryParameter("action"));
	}

	@Test
	public void aResponseThatPassedThroughAnotherHostIsRefused() throws Exception
	{
		GuideService detoured = new GuideService(answering(chain ->
		{
			Response elsewhere = new Response.Builder()
				.request(new Request.Builder().url("https://elsewhere.example/api.php").build())
				.protocol(Protocol.HTTP_1_1).code(302).message("Found").build();
			// the FINAL hop is the right host again - only the chain gives it away
			return ok(chain.request(), wikiJson(V1)).priorResponse(elsewhere).build();
		}), new Gson(), dir, new WikitextParser());

		Object[] outcome = fetchOnce(detoured);
		assertNull(outcome[0]);
		assertTrue((String) outcome[1], ((String) outcome[1]).contains("unexpected host"));
		assertFalse(detoured.hasSnapshot(ID));
	}

	@Test
	public void everyHopMustBeHttpsOnTheExpectedHost()
	{
		Request wiki = new Request.Builder().url("https://oldschool.runescape.wiki/api.php").build();
		Request plain = new Request.Builder().url("http://oldschool.runescape.wiki/api.php").build();
		Request other = new Request.Builder().url("https://oldschool.runescape.wiki.example/api.php").build();
		Response direct = ok(wiki, "{}").build();
		assertTrue(GuideService.cameOnlyFrom(direct, "oldschool.runescape.wiki"));
		assertFalse(GuideService.cameOnlyFrom(direct, "raw.githubusercontent.com"));
		assertFalse(GuideService.cameOnlyFrom(ok(plain, "{}").build(), "oldschool.runescape.wiki"));
		assertFalse(GuideService.cameOnlyFrom(ok(other, "{}").build(), "oldschool.runescape.wiki"));

		Response viaPlain = ok(wiki, "{}").priorResponse(
			new Response.Builder().request(plain).protocol(Protocol.HTTP_1_1).code(301).message("Moved").build()).build();
		assertFalse(GuideService.cameOnlyFrom(viaPlain, "oldschool.runescape.wiki"));
		Response viaSame = ok(wiki, "{}").priorResponse(
			new Response.Builder().request(wiki).protocol(Protocol.HTTP_1_1).code(408).message("Retry").build()).build();
		assertTrue(GuideService.cameOnlyFrom(viaSame, "oldschool.runescape.wiki"));
	}

	@Test
	public void redirectsAreNeverFollowedAndCallsAreTimeBounded() throws Exception
	{
		OkHttpClient shared = new OkHttpClient();
		assertTrue("precondition: the shared client follows redirects", shared.followRedirects());
		for (Object owner : new Object[]{
			new GuideService(shared, new Gson(), dir, new WikitextParser()),
			new LocationDbDownloader(shared, new NpcLocationStore(new Gson(), new File(dir, "npc.json")))})
		{
			java.lang.reflect.Field field = owner.getClass().getDeclaredField("okHttpClient");
			field.setAccessible(true);
			OkHttpClient derived = (OkHttpClient) field.get(owner);
			assertFalse(owner.getClass().getSimpleName(), derived.followRedirects());
			assertFalse(owner.getClass().getSimpleName(), derived.followSslRedirects());
			assertTrue(owner.getClass().getSimpleName(), derived.callTimeoutMillis() > 0);
		}
	}
}
