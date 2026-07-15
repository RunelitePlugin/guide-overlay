package com.hcimguide;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time, user-confirmed download of the full-game NPC location database.
 *
 * Source: mejrs/data_osrs (NPCList_OSRS.json) - the community dataset behind
 * the OSRS wiki's interactive map, generated from the game cache. Parsed with
 * a streaming reader (the file is several MB), first spawn point per NPC name
 * wins, and the result is merged WITHOUT overwriting anything the plugin has
 * already observed or been given (learned > saved > seed > downloaded).
 *
 * Like the guide import, this never runs automatically - only from the menu
 * after a confirmation dialog.
 */
@Singleton
public class LocationDbDownloader
{
	private static final Logger log = LoggerFactory.getLogger(LocationDbDownloader.class);

	/**
	 * Before a Plugin Hub submission, pin this to a commit SHA instead of
	 * "master" so the download is reproducible and review-friendly:
	 * {@code git ls-remote https://github.com/mejrs/data_osrs master}
	 * and replace "master" below with the returned hash.
	 */
	static final String SOURCE_URL =
		"https://raw.githubusercontent.com/mejrs/data_osrs/master/NPCList_OSRS.json";

	/** Refuse absurd responses before parsing. */
	private static final long MAX_BODY_BYTES = 64L * 1024 * 1024;
	private static final int MAX_ENTRIES = 50_000;

	private final okhttp3.OkHttpClient okHttpClient;
	private final NpcLocationStore store;

	@Inject
	public LocationDbDownloader(okhttp3.OkHttpClient okHttpClient, NpcLocationStore store)
	{
		this.okHttpClient = okHttpClient;
		this.store = store;
	}

	/**
	 * Exactly one of onSuccess (number of NEW locations added) / onError is
	 * invoked, on an OkHttp worker thread. Parsing runs on that worker thread
	 * DELIBERATELY: the reader streams straight off the socket (bounded by
	 * MAX_BODY_BYTES), so the multi-MB file is never buffered whole, and the
	 * store merge is thread-safe (putIfAbsent on a concurrent map). Nothing
	 * here touches the client thread or the EDT.
	 */
	public void download(Consumer<Integer> onSuccess, Consumer<String> onError)
	{
		Request request = new Request.Builder()
			.url(SOURCE_URL)
			.header("User-Agent", "guide-overlay RuneLite plugin")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Location database download failed", e);
				safe(onError, "Download failed: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						safe(onError, "Source returned HTTP " + response.code());
						return;
					}
					if (body.contentLength() > MAX_BODY_BYTES)
					{
						safe(onError, "Response implausibly large - aborted");
						return;
					}
					// hard byte cap that also covers chunked responses (where
					// contentLength() is -1) and pathological single values
					java.io.InputStream bounded = new java.io.FilterInputStream(body.byteStream())
					{
						private long count;

						private void bump(long n) throws IOException
						{
							count += n;
							if (count > MAX_BODY_BYTES)
							{
								throw new IOException("Response exceeded size limit");
							}
						}

						@Override
						public int read() throws IOException
						{
							int r = super.read();
							if (r >= 0)
							{
								bump(1);
							}
							return r;
						}

						@Override
						public int read(byte[] b, int off, int len) throws IOException
						{
							int r = super.read(b, off, len);
							if (r > 0)
							{
								bump(r);
							}
							return r;
						}
					};
					Map<String, int[]> parsed = parse(new JsonReader(
						new java.io.InputStreamReader(bounded, java.nio.charset.StandardCharsets.UTF_8)));
					if (parsed.isEmpty())
					{
						safe(onError, "No usable locations in the dataset");
						return;
					}
					int added = store.addNormalizedIfAbsent(parsed);
					store.saveIfDirty();
					try
					{
						onSuccess.accept(added);
					}
					catch (Exception e)
					{
						log.warn("Download success handler failed", e);
					}
				}
				catch (Exception e)
				{
					log.warn("Location database parse failed", e);
					safe(onError, "Parse failed: " + e.getMessage());
				}
			}
		});
	}

	/**
	 * Streams the JSON array, keeping name/x/y/p per entry and skipping the
	 * rest. First occurrence of each (normalized) name wins.
	 */
	static Map<String, int[]> parse(JsonReader reader) throws IOException
	{
		Map<String, int[]> out = new HashMap<>();
		reader.beginArray();
		while (reader.hasNext() && out.size() < MAX_ENTRIES)
		{
			String name = null;
			int x = -1;
			int y = -1;
			int p = 0;
			reader.beginObject();
			while (reader.hasNext())
			{
				String field = reader.nextName();
				switch (field)
				{
					case "name":
						if (reader.peek() == JsonToken.STRING)
						{
							name = reader.nextString();
						}
						else
						{
							reader.skipValue();
						}
						break;
					// peek-guard the numeric fields so ONE malformed record in a
					// 40k-entry community file skips that entry instead of
					// aborting the entire download
					case "x":
						x = readIntOrSkip(reader, -1);
						break;
					case "y":
						y = readIntOrSkip(reader, -1);
						break;
					case "p":
						p = readIntOrSkip(reader, -1);
						break;
					default:
						reader.skipValue();
						break;
				}
			}
			reader.endObject();

			if (name != null && !name.isEmpty() && !"null".equals(name)
				&& x >= 0 && x < 20000 && y >= 0 && y < 20000 && p >= 0 && p <= 3)
			{
				String norm = Names.normalize(name);
				if (!norm.isEmpty())
				{
					out.putIfAbsent(norm, new int[]{x, y, p});
				}
			}
		}
		return out;
	}

	/** Reads an int, or consumes the value and returns fallback when it isn't one. */
	private static int readIntOrSkip(JsonReader reader, int fallback) throws IOException
	{
		if (reader.peek() != JsonToken.NUMBER)
		{
			reader.skipValue();
			return fallback;
		}
		double d = reader.nextDouble();
		int i = (int) d;
		return i == d ? i : fallback;
	}

	private static void safe(Consumer<String> onError, String message)
	{
		try
		{
			onError.accept(message);
		}
		catch (Exception e)
		{
			log.warn("Download error handler failed", e);
		}
	}
}
