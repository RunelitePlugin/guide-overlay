package com.hcimguide;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Serializes progress (the set of completed step keys) to a compact,
 * clipboard-friendly text code and back: sorted keys joined by newlines,
 * gzipped, base64-encoded, with a versioned prefix so future formats can
 * be told apart. Pure Java - no plugin dependencies - so it is unit-testable
 * anywhere.
 */
public final class ProgressCodec
{
	static final String PREFIX = "HCIMGUIDE1:";

	/** Decoded payloads larger than this are rejected (a valid one is ~100KB max). */
	private static final int MAX_DECODED_BYTES = 4 * 1024 * 1024;

	/** Key-count cap so a hostile shared code can't bloat RuneLite's config store. */
	private static final int MAX_KEYS = 50_000;

	private ProgressCodec()
	{
	}

	/** Decoded result: the keys plus the guide id the code was exported from (may be null for old codes). */
	public static class Decoded
	{
		public final Set<String> keys;
		public final String guideId;

		Decoded(Set<String> keys, String guideId)
		{
			this.keys = keys;
			this.guideId = guideId;
		}
	}

	public static String encode(Set<String> completedKeys, String guideId) throws IOException
	{
		StringBuilder sb = new StringBuilder();
		if (guideId != null && !guideId.isEmpty())
		{
			sb.append("#guide:").append(guideId).append('\n');
		}
		// sorted so identical progress always yields an identical code
		sb.append(String.join("\n", new TreeSet<>(completedKeys)));
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(bos))
		{
			gz.write(sb.toString().getBytes(StandardCharsets.UTF_8));
		}
		return PREFIX + Base64.getEncoder().encodeToString(bos.toByteArray());
	}

	/**
	 * @throws IllegalArgumentException when the text is not a valid progress code
	 */
	public static Decoded decode(String text)
	{
		if (text == null)
		{
			throw new IllegalArgumentException("Clipboard is empty");
		}
		String trimmed = text.trim();
		if (!trimmed.startsWith(PREFIX))
		{
			throw new IllegalArgumentException("Not a progress code (missing " + PREFIX + " prefix)");
		}
		try
		{
			byte[] compressed = Base64.getDecoder().decode(trimmed.substring(PREFIX.length()));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed)))
			{
				byte[] buf = new byte[8192];
				int n;
				while ((n = gz.read(buf)) > 0)
				{
					out.write(buf, 0, n);
					if (out.size() > MAX_DECODED_BYTES)
					{
						throw new IllegalArgumentException("Progress code is implausibly large");
					}
				}
			}
			Set<String> keys = new HashSet<>();
			String guideId = null;
			for (String line : out.toString(StandardCharsets.UTF_8.name()).split("\n"))
			{
				if (line.isEmpty())
				{
					continue;
				}
				if (line.startsWith("#"))
				{
					// metadata lines; unknown ones are ignored for forward compatibility
					if (line.startsWith("#guide:"))
					{
						guideId = line.substring("#guide:".length()).trim();
					}
					continue;
				}
				keys.add(line);
				if (keys.size() > MAX_KEYS)
				{
					throw new IllegalArgumentException("Progress code has too many entries");
				}
			}
			return new Decoded(keys, guideId);
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new IllegalArgumentException("Progress code is corrupted: " + e.getMessage(), e);
		}
	}
}
