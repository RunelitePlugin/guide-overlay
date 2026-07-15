package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages locally stored guide snapshots, one per guide id.
 *
 * Every guide is imported ONCE (explicitly, by the user - from the wiki or
 * from a file) and stored on disk. The plugin never contacts the network on
 * its own, so wiki edits or vandalism can never silently change a guide or
 * reset progress. Each guide's previous snapshot is kept as a backup and can
 * be restored.
 */
@Singleton
public class GuideService
{
	private static final Logger log = LoggerFactory.getLogger(GuideService.class);

	private static final File DATA_DIR = new File(RuneLite.RUNELITE_DIR, "hcim-guide");
	private static final File GUIDES_DIR = new File(DATA_DIR, "guides");
	/** Pre-multi-guide store location, migrated to the built-in guide's slot. */
	private static final File LEGACY_FILE = new File(DATA_DIR, "guide-wikitext.txt");

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final WikitextParser parser = new WikitextParser();
	private final JsonGuideParser jsonParser = new JsonGuideParser();

	/**
	 * Parse dispatch: structured-JSON guides (BRUHsailer) vs wikitext (wiki
	 * guides). Content-sniffed, so a snapshot needs no format tag - the stored
	 * text speaks for itself.
	 */
	private Guide parseGuide(String text)
	{
		if (JsonGuideParser.looksLikeJsonGuide(text))
		{
			return jsonParser.parse(text);
		}
		return parser.parse(text);
	}

	@Inject
	public GuideService(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
	}

	private static File snapshotFile(String guideId)
	{
		return new File(GUIDES_DIR, guideId + ".txt");
	}

	private static File backupFile(String guideId)
	{
		return new File(GUIDES_DIR, guideId + ".bak");
	}

	/** One-time move of the pre-multi-guide snapshot into the built-in guide's slot. */
	public void migrateLegacyStore()
	{
		try
		{
			File target = snapshotFile(GuideRegistry.BUILTIN_ID);
			if (LEGACY_FILE.isFile() && !target.isFile())
			{
				//noinspection ResultOfMethodCallIgnored
				GUIDES_DIR.mkdirs();
				Files.move(LEGACY_FILE.toPath(), target.toPath());
			}
		}
		catch (IOException e)
		{
			log.warn("Legacy guide store migration failed", e);
		}
	}

	public boolean hasSnapshot(String guideId)
	{
		return snapshotFile(guideId).isFile();
	}

	/** Parses the stored snapshot for a guide, or returns null when none exists. */
	public Guide loadStored(String guideId)
	{
		try
		{
			File f = snapshotFile(guideId);
			if (f.isFile())
			{
				String wikitext = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
				Guide g = parseGuide(wikitext);
				if (!g.isEmpty())
				{
					return g;
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to read or parse stored guide {}", guideId, e);
		}
		return null;
	}

	/**
	 * Imports guide wikitext supplied directly (e.g. read from a user-chosen
	 * file), stores it as the guide's new snapshot, and returns the parsed guide.
	 *
	 * @throws IllegalArgumentException when the text is not parseable as a guide
	 */
	public Guide importText(String guideId, String wikitext) throws IOException
	{
		Guide guide = parseGuide(wikitext);
		if (guide.isEmpty())
		{
			throw new IllegalArgumentException("Text could not be parsed as a guide (no sections with steps found)");
		}
		// a failed disk write propagates: the source file still exists, so
		// failing loudly beats pretending the guide was stored
		saveSnapshot(guideId, wikitext);
		return guide;
	}

	/**
	 * Swaps a guide's stored snapshot with its previous one, if a backup
	 * exists. Returns the restored guide, or null when there is nothing to
	 * restore.
	 */
	public Guide restorePrevious(String guideId)
	{
		try
		{
			File snapshot = snapshotFile(guideId);
			File backup = backupFile(guideId);
			if (!backup.isFile())
			{
				return null;
			}
			String backupText = new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8);
			Guide guide = parseGuide(backupText);
			if (guide.isEmpty())
			{
				return null;
			}
			String currentText = snapshot.isFile()
				? new String(Files.readAllBytes(snapshot.toPath()), StandardCharsets.UTF_8)
				: null;
			Files.write(snapshot.toPath(), backupText.getBytes(StandardCharsets.UTF_8));
			if (currentText != null)
			{
				Files.write(backup.toPath(), currentText.getBytes(StandardCharsets.UTF_8));
			}
			return guide;
		}
		catch (Exception e)
		{
			log.warn("Failed to restore previous snapshot for guide {}", guideId, e);
			return null;
		}
	}

	/**
	 * Fetches a wiki page's wikitext asynchronously and stores it as the
	 * guide's snapshot. Only ever called from an explicit, user-confirmed
	 * action. Exactly one of onSuccess/onError is invoked, on an OkHttp
	 * worker thread. onSuccess's second argument is a nullable STORAGE
	 * WARNING: non-null means the guide parsed fine but could not be written
	 * to disk (it will need re-importing after a restart) - the user must be
	 * told rather than shown a false "stored locally".
	 */
	public void fetch(String guideId, String pageTitle, java.util.function.BiConsumer<Guide, String> onSuccess, Consumer<String> onError)
	{
		HttpUrl url = HttpUrl.get("https://oldschool.runescape.wiki/api.php").newBuilder()
			.addQueryParameter("action", "parse")
			.addQueryParameter("page", pageTitle)
			.addQueryParameter("prop", "wikitext")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2")
			.build();

		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", "guide-overlay RuneLite plugin")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Guide download failed", e);
				safeError(onError, "Download failed: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				Guide guide = null;
				String error = null;
				String storageWarning = null;
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						error = "Wiki returned HTTP " + response.code();
					}
					else
					{
						JsonObject root = gson.fromJson(readBounded(body), JsonObject.class);
						if (root == null || !root.has("parse"))
						{
							error = root != null && root.has("error")
								? "Wiki page not found"
								: "Unexpected wiki API response";
						}
						else
						{
							String wikitext = root.getAsJsonObject("parse").get("wikitext").getAsString();
							guide = parseGuide(wikitext);
							if (guide.isEmpty())
							{
								guide = null;
								error = "That page has no parseable guide steps";
							}
							else
							{
								try
								{
									saveSnapshot(guideId, wikitext);
								}
								catch (IOException e)
								{
									log.warn("Snapshot write failed", e);
									storageWarning = "Guide imported but could NOT be saved to disk ("
										+ e.getMessage() + ") - check free space/permissions on ~/.runelite; "
										+ "it will need re-importing after a restart";
								}
							}
						}
					}
				}
				catch (Exception e)
				{
					log.warn("Guide parse failed", e);
					error = "Parse failed: " + e.getMessage();
				}

				// exactly one callback fires, and a throwing consumer can't trigger the
				// other or propagate into the OkHttp dispatcher thread
				if (guide != null)
				{
					try
					{
						onSuccess.accept(guide, storageWarning);
					}
					catch (Exception e)
					{
						log.warn("Guide success handler failed", e);
					}
				}
				else
				{
					safeError(onError, error != null ? error : "Unknown error");
				}
			}
		});
	}

	/** Body cap for guide downloads (a real guide is well under 1MB). */
	private static final long MAX_GUIDE_BYTES = 16L * 1024 * 1024;

	/**
	 * Reads a response body while COUNTING BYTES, aborting past the cap.
	 * Content-Length can't be trusted for this: transparent gzip decoding
	 * makes OkHttp report -1, and a hostile server can lie - so the only
	 * reliable bound is counting what actually comes off the wire.
	 */
	private static String readBounded(ResponseBody body) throws IOException
	{
		java.io.InputStream in = body.byteStream();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0)
		{
			out.write(buf, 0, n);
			if (out.size() > MAX_GUIDE_BYTES)
			{
				throw new IOException("guide download exceeds " + (MAX_GUIDE_BYTES / (1024 * 1024)) + "MB - aborted");
			}
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	/**
	 * Fetches a built-in guide directly from a trusted raw URL (e.g. the
	 * BRUHsailer JSON) and stores it as the snapshot. The URL is only ever a
	 * hardcoded built-in source, never user-supplied. Same one-of/exactly-one
	 * callback contract as {@link #fetch}.
	 */
	public void fetchUrl(String guideId, String sourceUrl, java.util.function.BiConsumer<Guide, String> onSuccess, Consumer<String> onError)
	{
		Request request = new Request.Builder()
			.url(sourceUrl)
			.header("User-Agent", "guide-overlay RuneLite plugin")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Guide download failed", e);
				safeError(onError, "Download failed: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				Guide guide = null;
				String error = null;
				String storageWarning = null;
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						error = "Source returned HTTP " + response.code();
					}
					else
					{
						String text = readBounded(body);
						guide = parseGuide(text);
						if (guide.isEmpty())
						{
							guide = null;
							error = "The downloaded guide has no parseable steps";
						}
						else
						{
							try
							{
								saveSnapshot(guideId, text);
							}
							catch (IOException e)
							{
								log.warn("Snapshot write failed", e);
								storageWarning = "Guide imported but could NOT be saved to disk ("
									+ e.getMessage() + ") - check free space/permissions on ~/.runelite; "
									+ "it will need re-importing after a restart";
							}
						}
					}
				}
				catch (Exception e)
				{
					log.warn("Guide parse failed", e);
					error = "Parse failed: " + e.getMessage();
				}

				if (guide != null)
				{
					try
					{
						onSuccess.accept(guide, storageWarning);
					}
					catch (Exception e)
					{
						log.warn("Guide success handler failed", e);
					}
				}
				else
				{
					safeError(onError, error != null ? error : "Unknown error");
				}
			}
		});
	}

	private static void safeError(Consumer<String> onError, String message)
	{
		try
		{
			onError.accept(message);
		}
		catch (Exception e)
		{
			log.warn("Guide error handler failed", e);
		}
	}

	private static void saveSnapshot(String guideId, String wikitext) throws IOException
	{
		//noinspection ResultOfMethodCallIgnored
		GUIDES_DIR.mkdirs();
		File snapshot = snapshotFile(guideId);
		// keep the previous snapshot so a bad import can be undone
		if (snapshot.isFile())
		{
			Files.copy(snapshot.toPath(), backupFile(guideId).toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		Files.write(snapshot.toPath(), wikitext.getBytes(StandardCharsets.UTF_8));
	}
}
