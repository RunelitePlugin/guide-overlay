package com.hcimguide;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Guide screenshots: link recognition, parser placement, untrusted-image decoding, loading. */
public class GuideScreenshotTest
{
	private static final String A = "https://i.ibb.co/Abc1234/image.png";
	private static final String B = "https://i.ibb.co/Xyz98765/qh-V2fg-I.png";

	// ---------------------------------------------------------------- links

	@Test
	public void acceptsOnlyThePlainImageHostShape()
	{
		assertEquals(A, GuideImageLinks.accepted(A));
		assertEquals(B, GuideImageLinks.accepted(B));
		assertEquals("https://i.ibb.co/Abc1234/shot.JPG", GuideImageLinks.accepted("https://i.ibb.co/Abc1234/shot.JPG"));
		for (String bad : Arrays.asList(
			null,
			"",
			"http://i.ibb.co/Abc1234/image.png",
			"HTTPS://i.ibb.co/Abc1234/image.png",
			"https://I.IBB.CO/Abc1234/image.png",
			"https://i.ibb.co.evil.example/Abc1234/image.png",
			"https://evil.example/i.ibb.co/image.png",
			"https://i.ibb.co@evil.example/Abc1234/image.png",
			"https://user@i.ibb.co/Abc1234/image.png",
			"https://i.ibb.co:8443/Abc1234/image.png",
			"https://i.ibb.co/Abc1234/image.png?x=1",
			"https://i.ibb.co/Abc1234/image.png#frag",
			"https://i.ibb.co/Abc1234/../image.png",
			"https://i.ibb.co/Abc1234/..png",
			"https://i.ibb.co/Abc1234/a/image.png",
			"https://i.ibb.co//image.png",
			"https://i.ibb.co/image.png",
			"https://i.ibb.co/Abc1234/image.svg",
			"https://i.ibb.co/Abc1234/image.png.exe",
			"https://i.ibb.co/Abc1234/im%61ge.png",
			"https://i.ibb.co/Abc1234/im age.png",
			"https://i.ibb.co/Abc1234/image\\.png",
			"https://i.ibb.co/Abc-234/image.png",
			"https://i.ibb.co/Abc1234/іmage.png",
			"https://i.ibb.co/Abc1234/" + String.join("", Collections.nCopies(90, "a")) + ".png",
			"javascript:alert(1)",
			"file:///etc/passwd"))
		{
			assertNull(String.valueOf(bad), GuideImageLinks.accepted(bad));
		}
	}

	@Test
	public void readsOnlyTheFirstTemplateArgument()
	{
		assertEquals(A, GuideImageLinks.fromExtimage("{{Extimage|" + A + "|right|400|}}"));
		assertEquals(A, GuideImageLinks.fromExtimage("{{extimage|" + A + "}}"));
		// text typed after the closing braces is not part of the URL
		assertEquals(A, GuideImageLinks.fromExtimage("{{Extimage|" + A + "|right|400|}}weed"));
		assertNull(GuideImageLinks.fromExtimage("{{Extimage|https://evil.example/x.png|right|400|}}"));
		assertNull(GuideImageLinks.fromExtimage("{{Youtube|" + A + "}}"));
		assertNull(GuideImageLinks.fromExtimage("* Withdraw {{Extimage|" + A + "}}"));
		assertNull(GuideImageLinks.fromExtimage("{{Extimage|"
			+ String.join("", Collections.nCopies(100_000, "a")) + "|}}"));
		assertNull(GuideImageLinks.fromExtimage(null));
	}

	// ---------------------------------------------------------------- parser

	private static GuideBank bank(Guide guide, String title)
	{
		for (GuideEpisode ep : guide.getEpisodes())
		{
			for (GuideBank b : ep.getBanks())
			{
				if (b.getTitle().equals(title))
				{
					return b;
				}
			}
		}
		throw new AssertionError("no section " + title);
	}

	private static String image(String url)
	{
		return "{{Extimage|" + url + "|right|400|}}\n";
	}

	@Test
	public void screenshotBelongsToTheChecklistItPrecedes()
	{
		String c = "https://i.ibb.co/Ccc1234/image.png";
		String d = "https://i.ibb.co/Ddd1234/image.png";
		String e = "https://i.ibb.co/Eee1234/image.png";
		String wikitext = ""
			+ "== Episode 1 - Banks 1 through 9: ==\n"
			// under the episode heading, before any section exists
			+ image(A)
			+ "<!-- Bank 1 -->\n"
			+ "{{Checklist|title=Bank {{#expr:{{#var:b}}}}|\n"
			+ "* Withdraw: Coins\n"
			+ "}}\n"
			// the wiki's usual order: image, then the bank comment
			+ "{{#vardefine:b|{{#expr:{{#var:b}}+1}}}}\n"
			+ image(B)
			+ "<!-- Bank 2 -->\n"
			+ "{{Checklist|title=Bank {{#expr:{{#var:b}}}}|\n"
			+ "* Walk to Varrock\n"
			+ "}}\n"
			// comment first, two images
			+ "<!-- Bank 3 -->\n"
			+ image(c)
			+ image(d)
			+ "{{Checklist|title=Bank {{#expr:{{#var:b}}}}|\n"
			+ "* Buy a spade\n"
			+ "}}\n"
			// before a continuation box of the same bank
			+ image(e)
			+ "{{Checklist|title=|* Dig\n"
			+ "}}\n"
			+ "<!-- Bank 4 -->\n"
			+ "{{Checklist|title=Bank {{#expr:{{#var:b}}}}|\n"
			+ "* No picture here\n"
			+ "}}\n";
		Guide guide = new WikitextParser().parse(wikitext);

		assertEquals(Collections.singletonList(A), bank(guide, "Bank 1").getImageUrls());
		assertEquals(Collections.singletonList(B), bank(guide, "Bank 2").getImageUrls());
		assertEquals(Arrays.asList(c, d, e), bank(guide, "Bank 3").getImageUrls());
		assertTrue(bank(guide, "Bank 4").getImageUrls().isEmpty());
		// an image line never becomes a step or part of one
		assertEquals(1, bank(guide, "Bank 1").getSteps().size());
		assertEquals(2, bank(guide, "Bank 3").getSteps().size());
	}

	@Test
	public void screenshotsDoNotChangeStepKeys()
	{
		String with = "== Episode 1 - Banks 1 through 9: ==\n"
			+ image(A) + "<!-- Bank 1 -->\n{{Checklist|title=x|\n* Withdraw: Coins\n* Walk\n}}\n";
		String without = with.replace(image(A), "");
		List<GuideStep> a = new WikitextParser().parse(with).getEpisodes().get(0).getBanks().get(0).getSteps();
		List<GuideStep> b = new WikitextParser().parse(without).getEpisodes().get(0).getBanks().get(0).getSteps();
		assertEquals(a.size(), b.size());
		for (int i = 0; i < a.size(); i++)
		{
			assertEquals(b.get(i).getKey(), a.get(i).getKey());
			assertEquals(b.get(i).getText(), a.get(i).getText());
		}
	}

	@Test
	public void screenshotNeverCrossesAnEpisodeAndRejectedLinksAreDropped()
	{
		String wikitext = ""
			+ "== Episode 1 - Banks 1 through 9: ==\n"
			+ "<!-- Bank 1 -->\n{{Checklist|title=x|\n* Step one\n}}\n"
			+ image(A)
			+ "== Episode 2 - Banks 10 through 19: ==\n"
			+ image("https://evil.example/Abc1234/image.png")
			+ image("http://i.ibb.co/Abc1234/image.png")
			+ "<!-- Bank 10 -->\n{{Checklist|title=x|\n* Step two\n}}\n";
		Guide guide = new WikitextParser().parse(wikitext);
		assertTrue(bank(guide, "Bank 1").getImageUrls().isEmpty());
		assertTrue(bank(guide, "Bank 10").getImageUrls().isEmpty());
	}

	@Test
	public void screenshotsPerSectionAreCappedAndDeduplicated()
	{
		StringBuilder wikitext = new StringBuilder("== Episode 1 - Banks 1 through 9: ==\n");
		wikitext.append(image(A)).append(image(A));
		for (int i = 0; i < 50; i++)
		{
			wikitext.append(image("https://i.ibb.co/Img" + (1000 + i) + "/image.png"));
		}
		wikitext.append("<!-- Bank 1 -->\n{{Checklist|title=x|\n* Step one\n}}\n");
		List<String> images = bank(new WikitextParser().parse(wikitext.toString()), "Bank 1").getImageUrls();
		assertEquals(GuideImageLinks.MAX_PER_BANK, images.size());
		assertEquals(images.size(), new java.util.HashSet<>(images).size());
	}

	// ---------------------------------------------------------------- browser

	@Test
	public void browserOnlyEverGetsAnAcceptedScreenshotUrl()
	{
		List<String> opened = new ArrayList<>();
		GuideExternalActions actions = new GuideExternalActions(opened::add);
		assertTrue(actions.openImageUrl(A));
		assertFalse(actions.openImageUrl("https://evil.example/Abc1234/image.png"));
		assertFalse(actions.openImageUrl("https://www.youtube.com/watch?v=aBcD"));
		assertFalse(actions.openImageUrl(null));
		assertEquals(Collections.singletonList(A), opened);
		// a launcher that throws is reported, not propagated to the EDT
		GuideExternalActions failing = new GuideExternalActions(url ->
		{
			throw new IllegalArgumentException("no browser");
		});
		assertFalse(failing.openImageUrl(A));
	}

	// ---------------------------------------------------------------- decoding

	private static byte[] png(int width, int height) throws IOException
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		image.setRGB(width / 2, height / 2, 0xFF8800);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	@Test
	public void thumbnailFitsThePanelAndNeverEnlarges()
	{
		try
		{
			BufferedImage wide = GuideImageLoader.thumbnail(png(800, 400), 197);
			assertEquals(197, wide.getWidth());
			assertEquals(99, wide.getHeight());

			BufferedImage tall = GuideImageLoader.thumbnail(png(300, 3000), 197);
			assertEquals(GuideImageLoader.MAX_THUMB_HEIGHT, tall.getHeight());
			assertEquals(48, tall.getWidth());

			BufferedImage small = GuideImageLoader.thumbnail(png(60, 40), 197);
			assertEquals(60, small.getWidth());
			assertEquals(40, small.getHeight());
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void refusesAnythingThatIsNotAnAcceptableImage() throws IOException
	{
		assertNull(GuideImageLoader.thumbnail(new byte[0], 197));
		assertNull(GuideImageLoader.thumbnail("<html><script>alert(1)</script></html>".getBytes("UTF-8"), 197));
		// SVG, GIF and BMP are not accepted formats whatever the URL said
		assertNull(GuideImageLoader.thumbnail("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes("UTF-8"), 197));
		assertNull(GuideImageLoader.thumbnail("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000".getBytes("ISO-8859-1"), 197));

		byte[] real = png(64, 64);
		// truncated mid-data: the decoder's failure is a refusal, not a throw
		assertNull(GuideImageLoader.thumbnail(Arrays.copyOf(real, real.length / 2), 197));

		// a valid signature whose header declares a huge canvas is refused
		// from the header alone (bytes 16..23 of a PNG are width and height)
		byte[] bomb = real.clone();
		bomb[16] = 0x00;
		bomb[17] = 0x00;
		bomb[18] = (byte) 0xEA;
		bomb[19] = 0x60; // 60,000 wide
		bomb[20] = 0x00;
		bomb[21] = 0x00;
		bomb[22] = (byte) 0xEA;
		bomb[23] = 0x60; // 60,000 tall
		assertNull(GuideImageLoader.thumbnail(bomb, 197));

		// each side within the limit, the area not
		byte[] area = real.clone();
		area[16] = 0x00;
		area[17] = 0x00;
		area[18] = 0x10;
		area[19] = 0x00; // 4,096
		area[20] = 0x00;
		area[21] = 0x00;
		area[22] = 0x10;
		area[23] = 0x00; // 4,096
		assertNull(GuideImageLoader.thumbnail(area, 197));
	}

	// ---------------------------------------------------------------- loading

	private static Response.Builder reply(Request request, int code, byte[] body, String type)
	{
		return new Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("x")
			.body(ResponseBody.create(MediaType.parse(type), body));
	}

	private static BufferedImage await(GuideImageLoader loader, String url) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<BufferedImage> result = new AtomicReference<>();
		loader.load(url, 197, image ->
		{
			result.set(image);
			done.countDown();
		});
		assertTrue("callback did not run", done.await(10, TimeUnit.SECONDS));
		return result.get();
	}

	@Test
	public void loadsOnceThenServesFromMemory() throws Exception
	{
		byte[] body = png(400, 300);
		AtomicInteger requests = new AtomicInteger();
		List<String> seen = Collections.synchronizedList(new ArrayList<>());
		Interceptor interceptor = chain ->
		{
			requests.incrementAndGet();
			seen.add(chain.request().method() + " " + chain.request().url());
			return reply(chain.request(), 200, body, "image/png").build();
		};
		GuideImageLoader loader = new GuideImageLoader(new OkHttpClient.Builder().addInterceptor(interceptor).build());

		assertNull(loader.cached(A, 197));
		BufferedImage first = await(loader, A);
		assertNotNull(first);
		assertEquals(197, first.getWidth());
		assertNotNull(loader.cached(A, 197));
		assertNotNull(await(loader, A));
		assertEquals(1, requests.get());
		assertEquals(Collections.singletonList("GET " + A), seen);

		loader.clear();
		assertNull(loader.cached(A, 197));
	}

	@Test
	public void neverRequestsARejectedUrl() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		Interceptor interceptor = chain ->
		{
			requests.incrementAndGet();
			return reply(chain.request(), 200, png(10, 10), "image/png").build();
		};
		GuideImageLoader loader = new GuideImageLoader(new OkHttpClient.Builder().addInterceptor(interceptor).build());
		assertNull(await(loader, "https://evil.example/Abc1234/image.png"));
		assertNull(await(loader, "http://i.ibb.co/Abc1234/image.png"));
		assertNull(await(loader, "https://i.ibb.co/Abc1234/image.png?redirect=https://evil.example"));
		assertEquals(0, requests.get());
	}

	@Test
	public void refusesRedirectsOtherHostsErrorsAndJunk() throws Exception
	{
		byte[] good = png(100, 100);
		AtomicInteger requests = new AtomicInteger();
		AtomicReference<String> mode = new AtomicReference<>("redirect");
		Interceptor interceptor = chain ->
		{
			requests.incrementAndGet();
			Request request = chain.request();
			switch (mode.get())
			{
				case "redirect":
					return reply(request, 302, new byte[0], "text/plain")
						.header("Location", "https://evil.example/x.png").build();
				case "elsewhere":
					// a response that reports it was reached through another host
					Request other = new Request.Builder().url("https://evil.example/x.png").build();
					return reply(request, 200, good, "image/png")
						.priorResponse(new Response.Builder().request(other).protocol(Protocol.HTTP_1_1)
							.code(302).message("x").build())
						.build();
				case "missing":
					return reply(request, 404, good, "image/png").build();
				default:
					return reply(request, 200, "<html>not an image</html>".getBytes("UTF-8"), "image/png").build();
			}
		};
		GuideImageLoader loader = new GuideImageLoader(new OkHttpClient.Builder().addInterceptor(interceptor).build());

		assertNull(await(loader, "https://i.ibb.co/Redir001/image.png"));
		// the redirect target was never requested
		assertEquals(1, requests.get());
		mode.set("elsewhere");
		assertNull(await(loader, "https://i.ibb.co/Other001/image.png"));
		mode.set("missing");
		assertNull(await(loader, "https://i.ibb.co/Gone0001/image.png"));
		mode.set("junk");
		assertNull(await(loader, "https://i.ibb.co/Junk0001/image.png"));
		assertEquals(4, requests.get());

		// a URL the server already answered badly is not asked for again
		assertNull(await(loader, "https://i.ibb.co/Junk0001/image.png"));
		assertEquals(4, requests.get());
	}

	@Test
	public void oversizedBodyIsRefused() throws Exception
	{
		byte[] huge = new byte[GuideImageLoader.MAX_IMAGE_BYTES + 1];
		System.arraycopy(png(10, 10), 0, huge, 0, 60);
		Interceptor interceptor = chain -> reply(chain.request(), 200, huge, "image/png").build();
		GuideImageLoader loader = new GuideImageLoader(new OkHttpClient.Builder().addInterceptor(interceptor).build());
		assertNull(await(loader, A));
	}

	@Test
	public void clearDropsADownloadStillInFlight() throws Exception
	{
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		byte[] body = png(50, 50);
		Interceptor interceptor = chain ->
		{
			entered.countDown();
			try
			{
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			return reply(chain.request(), 200, body, "image/png").build();
		};
		OkHttpClient client = new OkHttpClient.Builder().addInterceptor(interceptor).build();
		GuideImageLoader loader = new GuideImageLoader(client);
		AtomicInteger callbacks = new AtomicInteger();
		loader.load(A, 197, image -> callbacks.incrementAndGet());
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		loader.clear();
		release.countDown();
		client.dispatcher().executorService().shutdown();
		assertTrue(client.dispatcher().executorService().awaitTermination(10, TimeUnit.SECONDS));
		assertEquals(0, callbacks.get());
		assertNull(loader.cached(A, 197));
	}
}
