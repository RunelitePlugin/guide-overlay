package com.hcimguide;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProgressCodecTest
{
	@Test
	public void roundTripsProgress() throws Exception
	{
		Set<String> keys = new HashSet<>();
		for (int i = 0; i < 500; i++)
		{
			keys.add("E1.B" + (i / 20) + "#" + Integer.toHexString(i * 31) + "#" + (i % 3));
		}
		String code = ProgressCodec.encode(keys, "b0aty-hcim-v3");
		assertTrue(code.startsWith("HCIMGUIDE1:"));
		ProgressCodec.Decoded decoded = ProgressCodec.decode(code);
		assertEquals(keys, decoded.keys);
		assertEquals("b0aty-hcim-v3", decoded.guideId);
	}

	@Test
	public void identicalProgressYieldsIdenticalCode() throws Exception
	{
		Set<String> a = new HashSet<>();
		a.add("k1");
		a.add("k2");
		Set<String> b = new HashSet<>();
		b.add("k2");
		b.add("k1");
		assertEquals(ProgressCodec.encode(a, "g"), ProgressCodec.encode(b, "g"));
	}

	@Test
	public void emptyProgressAndNullGuideIdRoundTrip() throws Exception
	{
		ProgressCodec.Decoded decoded = ProgressCodec.decode(ProgressCodec.encode(new HashSet<>(), null));
		assertTrue(decoded.keys.isEmpty());
		assertNull(decoded.guideId);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsMissingPrefix()
	{
		ProgressCodec.decode("not a code");
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsCorruptPayload()
	{
		ProgressCodec.decode("HCIMGUIDE1:%%%not-base64%%%");
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNull()
	{
		ProgressCodec.decode(null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsOversizedEncodedInput()
	{
		ProgressCodec.decode("HCIMGUIDE1:" + new String(new char[8 * 1024 * 1024 + 1]).replace('\0', 'A'));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNewlinesInKeys() throws Exception
	{
		Set<String> keys = new HashSet<>();
		keys.add("valid\nforged");
		ProgressCodec.encode(keys, "guide");
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsMetadataLookingKeys() throws Exception
	{
		Set<String> keys = new HashSet<>();
		keys.add("#guide:forged");
		ProgressCodec.encode(keys, "guide");
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsInvalidGuideId() throws Exception
	{
		ProgressCodec.encode(new HashSet<>(), "../../outside");
	}

	private static String codeFor(String payload) throws Exception
	{
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
		try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos))
		{
			gz.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		return ProgressCodec.PREFIX + java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
	}

	private static String repeat(String s, int n)
	{
		StringBuilder sb = new StringBuilder(s.length() * n);
		for (int i = 0; i < n; i++)
		{
			sb.append(s);
		}
		return sb.toString();
	}

	@Test
	public void rejectsAFewKilobytesThatInflateToMillionsOfLines() throws Exception
	{
		// under the decoded-size cap, and only ONE distinct key - the limit has
		// to count lines, not the size of the resulting set
		String code = codeFor(repeat("a\n", 2 * 1024 * 1024 - 8));
		org.junit.Assert.assertTrue(code.length() < 8000);
		try
		{
			ProgressCodec.decode(code);
			org.junit.Assert.fail("a line bomb must be rejected");
		}
		catch (IllegalArgumentException e)
		{
			org.junit.Assert.assertEquals("Progress code has too many entries", e.getMessage());
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsAnOverlongLineOfAnyKind() throws Exception
	{
		ProgressCodec.decode(codeFor("#future:" + repeat("x", 600) + "\nE1.B1#1#0"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsUnboundedMetadataLines() throws Exception
	{
		ProgressCodec.decode(codeFor(repeat("#x\n", 1000) + "E1.B1#1#0"));
	}

	@Test
	public void decodesLegacyShapesBlankLinesAndUnknownMetadata() throws Exception
	{
		// no guide line (oldest codes), blank lines, CR-free, trailing newline
		ProgressCodec.Decoded old = ProgressCodec.decode(codeFor("E1.B1#1a#0\n\nE1.B2#2b#1\n"));
		org.junit.Assert.assertNull(old.guideId);
		org.junit.Assert.assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("E1.B1#1a#0", "E1.B2#2b#1")), old.keys);

		ProgressCodec.Decoded tagged = ProgressCodec.decode(
			codeFor("#guide: b0aty-hcim-v3 \n#future:whatever\nE1.B1#1a#0"));
		org.junit.Assert.assertEquals("b0aty-hcim-v3", tagged.guideId);
		org.junit.Assert.assertEquals(java.util.Collections.singleton("E1.B1#1a#0"), tagged.keys);
	}

	@Test
	public void aFullSizeProgressSetStillRoundTrips() throws Exception
	{
		java.util.Set<String> keys = new java.util.HashSet<>();
		for (int i = 0; i < 50_000; i++)
		{
			keys.add("E1.B1#" + Integer.toHexString(i) + "#0");
		}
		org.junit.Assert.assertEquals(keys, ProgressCodec.decode(ProgressCodec.encode(keys, "x")).keys);
	}
}
