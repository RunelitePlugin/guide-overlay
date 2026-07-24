package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.regex.Pattern;
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
	private static final Pattern GUIDE_ID = Pattern.compile("[a-z0-9-]{1,64}");
	/** Body/on-disk cap for guide content (real guides are well under 1MB). */
	private static final long MAX_GUIDE_BYTES = 16L * 1024 * 1024;

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
		// single-pass: tryParse both sniffs AND parses, so a multi-MB JSON
		// import isn't parsed twice (once to identify, once to build)
		Guide json = jsonParser.tryParse(text);
		return json != null ? json : parser.parse(text);
	}

	/**
	 * The page wikitext out of a MediaWiki parse-API response, or null when
	 * the response has any other shape. Every level is type-checked: the API
	 * (or a proxy in between) can legally return "parse" as a non-object,
	 * omit "wikitext", or make it a non-string - none of which may throw.
	 */
	static String extractWikiText(JsonObject root)
	{
		if (root == null)
		{
			return null;
		}
		JsonElement parse = root.get("parse");
		if (parse == null || !parse.isJsonObject())
		{
			return null;
		}
		JsonElement text = parse.getAsJsonObject().get("wikitext");
		if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString())
		{
			return null;
		}
		return text.getAsString();
	}

	@Inject
	public GuideService(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
	}

	private static File snapshotFile(String guideId)
	{
		return new File(GUIDES_DIR, requireGuideId(guideId) + ".txt");
	}

	private static File backupFile(String guideId)
	{
		return new File(GUIDES_DIR, requireGuideId(guideId) + ".bak");
	}

	private static String requireGuideId(String guideId)
	{
		if (guideId == null || !GUIDE_ID.matcher(guideId).matches())
		{
			throw new IllegalArgumentException("Invalid guide id");
		}
		return guideId;
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
				String wikitext = readStoredText(f);
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
		validateGuideText(wikitext);
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

	/** Outcome of {@link #restorePrevious}: what happened, precisely. */
	public static final class RestoreResult
	{
		/** The restored guide; never null in a returned result. */
		final Guide guide;
		/** Non-null when the restore itself landed but the swap-back didn't. */
		final String warning;

		private RestoreResult(Guide guide, String warning)
		{
			this.guide = guide;
			this.warning = warning;
		}
	}

	/**
	 * Swaps a guide's stored snapshot with its previous one, if a backup
	 * exists. Returns null only when nothing was restored (no backup, or it
	 * no longer parses) - the on-disk state is untouched in that case. Both
	 * replacement files are fully staged as temps BEFORE the first move, and
	 * the SNAPSHOT is moved first: once it lands the restore has genuinely
	 * happened, so a failure writing the old text back into the backup slot
	 * is reported as a warning on a successful restore, never as "nothing
	 * was restored" while the disk actually changed.
	 */
	public RestoreResult restorePrevious(String guideId)
	{
		File snapshot = snapshotFile(guideId);
		File backup = backupFile(guideId);
		String backupText;
		Guide guide;
		String currentText;
		try
		{
			if (!backup.isFile())
			{
				return null;
			}
			backupText = readStoredText(backup);
			guide = parseGuide(backupText);
			if (guide.isEmpty())
			{
				return null;
			}
			currentText = snapshot.isFile() ? readStoredText(snapshot) : null;
			atomicWrite(snapshot, backupText.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			// nothing moved yet (atomicWrite stages a temp and moves once):
			// the stored state is intact and reporting "not restored" is true
			log.warn("Failed to restore previous snapshot for guide {}", guideId, e);
			return null;
		}
		if (currentText == null)
		{
			return new RestoreResult(guide, null);
		}
		try
		{
			atomicWrite(backup, currentText.getBytes(StandardCharsets.UTF_8));
			return new RestoreResult(guide, null);
		}
		catch (Exception e)
		{
			// the restore DID land; only the swap-back failed. Say exactly that.
			log.warn("Restored guide {} but could not write the swap-back backup", guideId, e);
			return new RestoreResult(guide,
				"Previous snapshot restored, but the replaced import could not be "
					+ "kept as the new backup (" + e.getMessage() + ") - restoring "
					+ "forward again may not be possible");
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
				// separate phases so a failure is reported as what it actually
				// is - a read error must never surface as "Parse failed"
				Guide guide = null;
				String error = null;
				String storageWarning = null;
				String bodyText = null;
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						error = "Wiki returned HTTP " + response.code();
					}
					else
					{
						bodyText = readBounded(body);
					}
				}
				catch (Exception e)
				{
					log.warn("Reading wiki response failed", e);
					error = "Could not read the wiki response: " + e.getMessage();
				}

				String wikitext = null;
				if (error == null)
				{
					try
					{
						JsonObject root = gson.fromJson(bodyText, JsonObject.class);
						wikitext = extractWikiText(root);
						if (wikitext == null)
						{
							error = root != null && root.has("error")
								? "Wiki page not found"
								: "Unexpected wiki API response";
						}
					}
					catch (Exception e)
					{
						log.warn("Wiki response was not valid JSON", e);
						error = "Wiki response was not valid JSON";
					}
				}

				if (error == null)
				{
					try
					{
						guide = parseGuide(wikitext);
						if (guide.isEmpty())
						{
							guide = null;
							error = "That page has no parseable guide steps";
						}
					}
					catch (Exception e)
					{
						log.warn("Guide parse failed", e);
						error = "Guide could not be parsed: " + e.getMessage();
					}
				}

				if (guide != null)
				{
					try
					{
						saveSnapshot(guideId, wikitext);
					}
					catch (Exception e)
					{
						log.warn("Snapshot write failed", e);
						storageWarning = "Guide imported but could NOT be saved to disk ("
							+ e.getMessage() + ") - check free space/permissions on ~/.runelite; "
							+ "it will need re-importing after a restart";
					}
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
	 * callback contract as {@link #fetch}, with one deliberate exception: a
	 * URL that fails validation reports onError SYNCHRONOUSLY on the calling
	 * thread, before any network work starts.
	 */
	public void fetchUrl(String guideId, String sourceUrl, java.util.function.BiConsumer<Guide, String> onSuccess, Consumer<String> onError)
	{
		HttpUrl parsedUrl;
		try
		{
			parsedUrl = HttpUrl.get(sourceUrl);
			if (!"https".equals(parsedUrl.scheme()) || !"raw.githubusercontent.com".equals(parsedUrl.host()))
			{
				throw new IllegalArgumentException("unsupported built-in guide source");
			}
		}
		catch (Exception e)
		{
			safeError(onError, "Built-in guide source is invalid");
			return;
		}
		Request request = new Request.Builder()
			.url(parsedUrl)
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
				// same phase separation as fetch(): read, parse, persist
				Guide guide = null;
				String error = null;
				String storageWarning = null;
				String text = null;
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						error = "Source returned HTTP " + response.code();
					}
					else
					{
						text = readBounded(body);
					}
				}
				catch (Exception e)
				{
					log.warn("Reading guide response failed", e);
					error = "Could not read the download: " + e.getMessage();
				}

				if (error == null)
				{
					try
					{
						guide = parseGuide(text);
						if (guide.isEmpty())
						{
							guide = null;
							error = "The downloaded guide has no parseable steps";
						}
					}
					catch (Exception e)
					{
						log.warn("Guide parse failed", e);
						error = "Guide could not be parsed: " + e.getMessage();
					}
				}

				if (guide != null)
				{
					try
					{
						saveSnapshot(guideId, text);
					}
					catch (Exception e)
					{
						log.warn("Snapshot write failed", e);
						storageWarning = "Guide imported but could NOT be saved to disk ("
							+ e.getMessage() + ") - check free space/permissions on ~/.runelite; "
							+ "it will need re-importing after a restart";
					}
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

	/**
	 * Stores the new snapshot, rotating the old one into the backup slot.
	 * Both files are fully staged as temps BEFORE either move, so a failure
	 * while preparing leaves the stored pair untouched. The backup rotates
	 * first: if the snapshot move then fails, the pair is still consistent
	 * (backup == the snapshot the user still has) and the thrown message
	 * says precisely that - the old backup is gone but nothing is corrupt.
	 */
	private static void saveSnapshot(String guideId, String wikitext) throws IOException
	{
		validateGuideText(wikitext);
		Files.createDirectories(GUIDES_DIR.toPath());
		File snapshot = snapshotFile(guideId);
		File backup = backupFile(guideId);
		Path dir = GUIDES_DIR.toPath();
		Path newSnapshot = Files.createTempFile(dir, snapshot.getName(), ".tmp");
		Path newBackup = null;
		try
		{
			Files.write(newSnapshot, wikitext.getBytes(StandardCharsets.UTF_8));
			if (snapshot.isFile())
			{
				// keep the previous snapshot so a bad import can be undone
				newBackup = Files.createTempFile(dir, backup.getName(), ".tmp");
				Files.copy(snapshot.toPath(), newBackup, StandardCopyOption.REPLACE_EXISTING);
				moveReplacing(newBackup, backup.toPath());
			}
			try
			{
				moveReplacing(newSnapshot, snapshot.toPath());
			}
			catch (IOException e)
			{
				throw new IOException("guide not saved (" + e.getMessage()
					+ "); the backup slot now holds the still-current snapshot", e);
			}
		}
		finally
		{
			Files.deleteIfExists(newSnapshot);
			if (newBackup != null)
			{
				Files.deleteIfExists(newBackup);
			}
		}
	}

	private static String readStoredText(File file) throws IOException
	{
		try (java.io.InputStream in = Files.newInputStream(file.toPath()))
		{
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) > 0)
			{
				out.write(buffer, 0, n);
				if (out.size() > MAX_GUIDE_BYTES)
				{
					throw new IOException("stored guide exceeds 16MB");
				}
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static void validateGuideText(String text) throws IOException
	{
		if (text == null)
		{
			throw new IOException("guide text is missing");
		}
		if (text.getBytes(StandardCharsets.UTF_8).length > MAX_GUIDE_BYTES)
		{
			throw new IOException("guide exceeds 16MB");
		}
	}

	private static void atomicWrite(File target, byte[] bytes) throws IOException
	{
		Path directory = target.getParentFile().toPath();
		Files.createDirectories(directory);
		Path temp = Files.createTempFile(directory, target.getName(), ".tmp");
		try
		{
			Files.write(temp, bytes);
			moveReplacing(temp, target.toPath());
		}
		finally
		{
			// after a successful move the temp path no longer exists and this
			// is a no-op; it only ever deletes a leftover from a FAILED write
			Files.deleteIfExists(temp);
		}
	}

	private static void moveReplacing(Path source, Path target) throws IOException
	{
		try
		{
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
