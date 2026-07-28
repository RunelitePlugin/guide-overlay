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
	/** Bound memory before Base64 decoding; normal progress codes are far smaller. */
	private static final int MAX_ENCODED_CHARS = 8 * 1024 * 1024;

	/** Key-count cap so a hostile shared code can't bloat RuneLite's config store. */
	private static final int MAX_KEYS = 50_000;
	/** Parsed step keys are compact hashes; this leaves ample forward-compatible room. */
	private static final int MAX_KEY_CHARS = 512;
	private static final java.util.regex.Pattern GUIDE_ID_PATTERN =
		java.util.regex.Pattern.compile("[a-z0-9-]{1,64}");

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
		if (completedKeys == null)
		{
			throw new IllegalArgumentException("Progress set is missing");
		}
		if (completedKeys.size() > MAX_KEYS)
		{
			throw new IllegalArgumentException("Progress has too many entries");
		}
		if (guideId != null && !guideId.isEmpty() && !GUIDE_ID_PATTERN.matcher(guideId).matches())
		{
			throw new IllegalArgumentException("Guide id is invalid");
		}
		StringBuilder sb = new StringBuilder();
		if (guideId != null && !guideId.isEmpty())
		{
			sb.append("#guide:").append(guideId).append('\n');
		}
		// sorted so identical progress always yields an identical code
		TreeSet<String> sorted = new TreeSet<>();
		for (String key : completedKeys)
		{
			validateKey(key);
			sorted.add(key);
		}
		sb.append(String.join("\n", sorted));
		byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
		if (payload.length > MAX_DECODED_BYTES)
		{
			throw new IllegalArgumentException("Progress is implausibly large");
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(bos))
		{
			gz.write(payload);
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
		if (text.length() > MAX_ENCODED_CHARS)
		{
			throw new IllegalArgumentException("Progress code is implausibly large");
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
						String candidate = line.substring("#guide:".length()).trim();
						if (!GUIDE_ID_PATTERN.matcher(candidate).matches())
						{
							throw new IllegalArgumentException("Progress code contains an invalid guide id");
						}
						guideId = candidate;
					}
					continue;
				}
				validateKey(line);
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

	private static void validateKey(String key)
	{
		if (key == null || key.isEmpty() || key.length() > MAX_KEY_CHARS || key.charAt(0) == '#')
		{
			throw new IllegalArgumentException("Progress contains an invalid step key");
		}
		for (int i = 0; i < key.length(); i++)
		{
			if (Character.isISOControl(key.charAt(i)))
			{
				throw new IllegalArgumentException("Progress contains an invalid step key");
			}
		}
	}
}
