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
	 * PINNED to a commit SHA so the download is reproducible and
	 * review-friendly. To update the dataset for a future release, replace
	 * the hash with the current one:
	 * {@code git ls-remote https://github.com/mejrs/data_osrs refs/heads/master}
	 * (tools/submit.sh re-pins automatically if this is ever set back to
	 * "master").
	 */
	static final String SOURCE_URL =
		"https://raw.githubusercontent.com/mejrs/data_osrs/6a3ca6f19d65c5609434b51cac8dee9d4af97c02/NPCList_OSRS.json";

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
	 * Exactly one of onSuccess (what was added, and whether the source was
	 * truncated at the safety cap) / onError is invoked, on an OkHttp worker
	 * thread. Parsing runs on that worker thread DELIBERATELY: the reader
	 * streams straight off the socket (bounded by MAX_BODY_BYTES), so the
	 * multi-MB file is never buffered whole, and the store merge is
	 * thread-safe (putIfAbsent on a concurrent map). Nothing here touches
	 * the client thread or the EDT.
	 */
	public void download(Consumer<DownloadResult> onSuccess, Consumer<String> onError)
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
					Parsed parsed = parse(new JsonReader(
						new java.io.InputStreamReader(bounded, java.nio.charset.StandardCharsets.UTF_8)));
					if (parsed.locations.isEmpty())
					{
						safe(onError, "No usable locations in the dataset");
						return;
					}
					int added = store.addNormalizedIfAbsent(parsed.locations);
					store.saveIfDirty();
					try
					{
						onSuccess.accept(new DownloadResult(added, parsed.truncated));
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

	/** Streamed parse outcome: the entries, plus whether the cap cut the source. */
	static final class Parsed
	{
		final Map<String, int[]> locations;
		final boolean truncated;

		Parsed(Map<String, int[]> locations, boolean truncated)
		{
			this.locations = locations;
			this.truncated = truncated;
		}
	}

	/** What a completed download did: how many entries landed, and honestly. */
	public static final class DownloadResult
	{
		/** New locations actually added to the store. */
		final int added;
		/** True when the source held more entries than the safety cap kept. */
		final boolean truncated;

		DownloadResult(int added, boolean truncated)
		{
			this.added = added;
			this.truncated = truncated;
		}
	}

	/**
	 * Streams the JSON array, keeping name/x/y/p per entry and skipping the
	 * rest. First occurrence of each (normalized) name wins.
	 */
	static Parsed parse(JsonReader reader) throws IOException
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
		// cap hit with entries remaining: skip to the array's actual end so
		// the stream finishes in a defined state, and report the truncation
		// instead of presenting a partial database as complete
		boolean truncated = false;
		while (reader.hasNext())
		{
			truncated = true;
			reader.skipValue();
		}
		reader.endArray();
		return new Parsed(out, truncated);
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
