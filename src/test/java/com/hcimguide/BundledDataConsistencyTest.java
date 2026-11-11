package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/** The bundled tables describe the same game: where two of them cover one fact, they must agree. */
public class BundledDataConsistencyTest
{
	@Test
	public void spellTeleportsCostTheSameInBothRuneTables() throws Exception
	{
		JsonObject spells;
		try (InputStream in = ItemListParser.class.getResourceAsStream("spell-runes.json"))
		{
			assertNotNull(in);
			spells = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonObject("spells");
		}
		int compared = 0;
		for (TeleportOption option : TeleportDirectory.ALL)
		{
			if (option.getNeeds().isEmpty() || !spells.has(option.getName()))
			{
				continue;
			}
			Map<String, Integer> expected = new HashMap<>();
			JsonObject cost = spells.getAsJsonObject(option.getName());
			for (String rune : cost.keySet())
			{
				expected.put(rune.toLowerCase(java.util.Locale.ROOT), cost.get(rune).getAsInt());
			}
			Map<String, Integer> actual = new HashMap<>();
			for (TeleportOption.ItemNeed need : option.getNeeds())
			{
				actual.put(need.getNamePart(), need.getQty());
			}
			assertEquals(option.getName(), expected, actual);
			compared++;
		}
		// Varrock, Lumbridge, Falador, Camelot, Ardougne, Kourend Castle
		assertEquals(6, compared);
	}

	@Test
	public void kourendCastleTeleportUsesItsCurrentRunes()
	{
		// two law, one water, one fire since the 10 January 2024 game update
		// (previously two law, two soul, four water, five fire)
		for (TeleportOption option : TeleportDirectory.ALL)
		{
			if (option.getName().equals("Kourend Castle Teleport"))
			{
				Map<String, Integer> needs = new HashMap<>();
				for (TeleportOption.ItemNeed need : option.getNeeds())
				{
					needs.put(need.getNamePart(), need.getQty());
				}
				assertEquals(3, needs.size());
				assertEquals(Integer.valueOf(2), needs.get("law rune"));
				assertEquals(Integer.valueOf(1), needs.get("water rune"));
				assertEquals(Integer.valueOf(1), needs.get("fire rune"));
				assertFalse(needs.containsKey("soul rune"));
				return;
			}
		}
		throw new AssertionError("Kourend Castle Teleport missing");
	}

	@Test
	public void soulWarsTeleportLandsInTheSoulWarsLobby()
	{
		Gson gson = new Gson();
		StepLocationHint place = new PlaceDirectory(gson).find("Go to the Soul Wars lobby");
		StepLocationHint teleport = new TransportResolver(gson).find("Minigame teleport to Soul Wars");
		assertNotNull(place);
		assertNotNull(teleport);
		assertEquals(place.getPoint(), teleport.getPoint());
	}
}
