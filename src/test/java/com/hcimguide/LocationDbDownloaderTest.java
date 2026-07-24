package com.hcimguide;

import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LocationDbDownloaderTest
{
	@Test
	public void parsesNameXYPAndSkipsUnknownFields() throws Exception
	{
		String json = "["
			+ "{\"name\": \"Hans\", \"id\": 3105, \"p\": 0, \"x\": 3221, \"y\": 3218,"
			+ " \"combatLevel\": 0, \"actions\": [\"Talk-to\", null], \"models\": [217]},"
			+ "{\"name\": \"Bird\", \"id\": 5241, \"p\": 0, \"x\": 2696, \"y\": 2709},"
			+ "{\"name\": \"Bird\", \"id\": 5241, \"p\": 0, \"x\": 2713, \"y\": 2697},"
			+ "{\"id\": 1, \"p\": 0, \"x\": 1, \"y\": 1},"
			+ "{\"name\": \"null\", \"p\": 0, \"x\": 5, \"y\": 5},"
			+ "{\"name\": \"BadPlane\", \"p\": 9, \"x\": 5, \"y\": 5}"
			+ "]";
		LocationDbDownloader.Parsed parsed =
			LocationDbDownloader.parse(new JsonReader(new StringReader(json)));
		Map<String, int[]> out = parsed.locations;

		assertEquals(2, out.size());
		assertArrayEquals(new int[]{3221, 3218, 0}, out.get("hans"));
		// first spawn per name wins
		assertArrayEquals(new int[]{2696, 2709, 0}, out.get("bird"));
		assertFalse(out.containsKey("null"));
		assertFalse(out.containsKey("badplane"));
		assertFalse(parsed.truncated);
	}

	@Test
	public void handlesEmptyArray() throws Exception
	{
		LocationDbDownloader.Parsed parsed =
			LocationDbDownloader.parse(new JsonReader(new StringReader("[]")));
		assertEquals(0, parsed.locations.size());
		assertFalse(parsed.truncated);
	}
}
