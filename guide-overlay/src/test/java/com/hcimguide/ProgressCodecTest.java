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
}
