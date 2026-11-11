package com.hcimguide;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static com.hcimguide.PluginProgressHarness.CHARACTER_A;
import static com.hcimguide.PluginProgressHarness.CHARACTER_B;
import static com.hcimguide.PluginProgressHarness.GROUP;
import static com.hcimguide.PluginProgressHarness.PROGRESS_KEY;
import static com.hcimguide.PluginProgressHarness.SKIPPED_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Progress-store rules of {@link HcimGuidePlugin}: which store a change lands
 * in, what a character switch reloads, and what manual control survives.
 */
public class ProgressStoreRegressionTest
{
	private PluginProgressHarness h;
	private HcimGuidePlugin plugin;

	@Before
	public void setUp()
	{
		h = new PluginProgressHarness();
		plugin = h.plugin;
	}

	@After
	public void tearDown()
	{
		if (h != null)
		{
			h.close();
		}
	}

	private static GuideStep step(String key)
	{
		return new GuideStep(key, "step " + key, 0, "E1.B1");
	}

	private Set<String> autoSuppressed()
	{
		return h.pluginField("autoSuppressed");
	}

	// ------------------------------------------------------------ key migration

	@Test
	public void characterProgressIsKeyMigratedWhenItLoadsOnLogin()
	{
		Guide guide = h.loadGuide(new String[]{"E1.B1#new1#0", "E1.B1#new2#0"});
		guide.getLegacySplitKeys().put("E1.B1#old#0", Arrays.asList("E1.B1#new1#0", "E1.B1#new2#0"));
		// ticked under the previous parser's key, in the character's own store
		h.configManager.setConfiguration(GROUP, CHARACTER_A, PROGRESS_KEY, "[\"E1.B1#old#0\"]");

		// the guide model was applied at the login screen, against the shared
		// store; the character's progress is only read now
		h.switchCharacter(null, CHARACTER_A);

		assertTrue(plugin.isCompleted("E1.B1#new1#0"));
		assertTrue(plugin.isCompleted("E1.B1#new2#0"));
		assertFalse("the legacy key is consumed", plugin.isCompleted("E1.B1#old#0"));
		String stored = h.characterStore(CHARACTER_A, PROGRESS_KEY);
		assertTrue("the migration is persisted to the character", stored.contains("E1.B1#new1#0"));
		assertFalse(stored.contains("E1.B1#old#0"));
	}

	@Test
	public void importedProgressCodeIsKeyMigratedBeforeUnknownKeysAreDropped()
	{
		Guide guide = h.loadGuide(new String[]{"E1.B1#new1#0", "E1.B1#new2#0", "E1.B1#other#0"});
		guide.getLegacySplitKeys().put("E1.B1#old#0", Arrays.asList("E1.B1#new1#0", "E1.B1#new2#0"));

		Set<String> imported = HcimGuidePlugin.importableProgressKeys(guide,
			new java.util.HashSet<>(Arrays.asList("E1.B1#old#0", "E1.B1#other#0", "E9.B9#gone#0")));

		assertEquals(new java.util.HashSet<>(Arrays.asList(
			"E1.B1#new1#0", "E1.B1#new2#0", "E1.B1#other#0")), imported);
	}

	// ------------------------------------------------------------ store routing

	@Test
	public void loggingOutSwapsToTheSharedStoreSoALoginScreenEditCannotLeak()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.setCompleted("E1.B1#aaaa#0", true);
		assertNull("nothing shared yet", h.sharedStore(PROGRESS_KEY));

		h.switchCharacter(CHARACTER_A, null); // log out
		assertFalse("the login screen shows the shared store, not character A",
			plugin.isCompleted("E1.B1#aaaa#0"));
		plugin.setCompleted("E1.B1#bbbb#0", true); // a click on the login screen

		assertFalse("character A's progress never reaches the shared store",
			h.sharedStore(PROGRESS_KEY).contains("E1.B1#aaaa#0"));
		assertTrue(h.sharedStore(PROGRESS_KEY).contains("E1.B1#bbbb#0"));

		h.switchCharacter(null, CHARACTER_A); // and A is untouched by the edit
		assertTrue(plugin.isCompleted("E1.B1#aaaa#0"));
		assertFalse(plugin.isCompleted("E1.B1#bbbb#0"));
	}

	@Test
	public void logoutIsHandledWhenTheGameStateChangesBeforeTheProfileIsDropped()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.setCompleted("E1.B1#aaaa#0", true);

		// LOGIN_SCREEN arrives while the manager still names character A...
		h.fireGameState(GameState.LOGIN_SCREEN);
		assertTrue(plugin.isCompleted("E1.B1#aaaa#0"));
		// ...then the profile key goes away without any further event
		h.setProfileKeyOnly(null);
		plugin.setCompleted("E1.B1#bbbb#0", true);

		assertNull("a write against A's in-memory sets is refused, not sent to the shared store",
			h.sharedStore(PROGRESS_KEY));
	}

	@Test
	public void sharedProgressSeedsOnlyTheFirstCharacter()
	{
		h.configManager.setConfiguration(GROUP, PROGRESS_KEY, "[\"E1.B1#shared#0\"]");
		h.configManager.setConfiguration(GROUP, SKIPPED_KEY, "[\"E1.B1#skipped#0\"]");

		h.switchCharacter(null, CHARACTER_A);
		assertTrue("the first character inherits the existing checklist",
			plugin.isCompleted("E1.B1#shared#0"));
		assertTrue(plugin.isSkipped("E1.B1#skipped#0"));
		assertTrue(h.characterStore(CHARACTER_A, PROGRESS_KEY).contains("E1.B1#shared#0"));

		h.switchCharacter(CHARACTER_A, null);
		h.switchCharacter(null, CHARACTER_B);
		assertFalse("a later character starts fresh", plugin.isCompleted("E1.B1#shared#0"));
		assertFalse(plugin.isSkipped("E1.B1#skipped#0"));
		assertNull(h.characterStore(CHARACTER_B, PROGRESS_KEY));
	}

	@Test
	public void sceneLoadsDoNotReReadAnAlreadyBoundStore()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.setCompleted("E1.B1#aaaa#0", true);
		// an out-of-band value a re-read would pick up
		h.configManager.setConfiguration(GROUP, CHARACTER_A, PROGRESS_KEY, "[\"E1.B1#outofband#0\"]");

		h.fireGameState(GameState.LOADING);
		h.fireGameState(GameState.LOGGED_IN); // every region crossing

		assertTrue(plugin.isCompleted("E1.B1#aaaa#0"));
		assertFalse(plugin.isCompleted("E1.B1#outofband#0"));
	}

	@Test
	public void nothingIsPersistedOnceThePluginIsInactive()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.setCompleted("E1.B1#aaaa#0", true);
		String before = h.characterStore(CHARACTER_A, PROGRESS_KEY);

		// shutDown drops `active` before it clears the sets; a mutation still
		// in flight on another thread must not persist what is left
		h.setPluginField("active", false);
		Set<String> completed = h.pluginField("completedSteps");
		synchronized (completed)
		{
			completed.clear();
		}
		plugin.setCompleted("E1.B1#late#0", true);
		plugin.setSkipped("E1.B1#late#0", true);

		assertEquals(before, h.characterStore(CHARACTER_A, PROGRESS_KEY));
		assertNull(h.characterStore(CHARACTER_A, SKIPPED_KEY));
	}

	@Test
	public void stateTheLoaderWouldDropIsNotWritten()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.setCompleted("E1.B1#aaaa#0", true);
		String before = h.characterStore(CHARACTER_A, PROGRESS_KEY);
		char[] long513 = new char[513];
		Arrays.fill(long513, 'k');
		String overlong = new String(long513); // the loader caps keys at 512 chars

		plugin.setCompleted(overlong, true);
		plugin.setSkipped(overlong, true);

		assertEquals(before, h.characterStore(CHARACTER_A, PROGRESS_KEY));
		assertNull(h.characterStore(CHARACTER_A, SKIPPED_KEY));
	}

	// ------------------------------------------------------------ bulk undo

	@Test
	public void bulkUndoRestoresOnlyIntoTheCharacterItWasTakenFrom()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.setCompleted("E1.B1#a1#0", true);
		plugin.snapshotBeforeBulk("mark bank complete");
		plugin.setCompletedBulk(Arrays.asList(step("E1.B1#a2#0"), step("E1.B1#a3#0")), true);

		h.switchCharacter(CHARACTER_A, null);
		h.configManager.setConfiguration(GROUP, CHARACTER_B, PROGRESS_KEY, "[\"E9.B9#bOnly#0\"]");
		h.switchCharacter(null, CHARACTER_B);

		assertFalse("character A's snapshot is not offered on character B", plugin.undoLastBulk());
		assertTrue(plugin.isCompleted("E9.B9#bOnly#0"));
		assertTrue(h.characterStore(CHARACTER_B, PROGRESS_KEY).contains("bOnly"));

		h.switchCharacter(CHARACTER_B, null);
		h.switchCharacter(null, CHARACTER_A);
		assertTrue("back on A the undo still works", plugin.undoLastBulk());
		assertTrue(plugin.isCompleted("E1.B1#a1#0"));
		assertFalse(plugin.isCompleted("E1.B1#a2#0"));
	}

	@Test
	public void aRefusedBulkActionDoesNotReplaceThePreviousUndoSnapshot()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.snapshotBeforeBulk("first");
		plugin.setCompletedBulk(Collections.singletonList(step("E1.B1#a1#0")), true);

		// logged in with no character profile selected: progress is read-only
		h.setProfileKeyOnly(null);
		plugin.snapshotBeforeBulk("second");
		plugin.setCompletedBulk(Collections.singletonList(step("E1.B1#a2#0")), true);
		assertFalse("the bulk itself was refused", plugin.isCompleted("E1.B1#a2#0"));

		h.setProfileKeyOnly(CHARACTER_A);
		assertTrue(plugin.undoLastBulk());
		assertFalse("the FIRST bulk action was undone", plugin.isCompleted("E1.B1#a1#0"));
	}

	// ------------------------------------------------------------ manual control

	@Test
	public void bulkUntickVetoesOnlyStepsItActuallyUnticked()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.setCompleted("E1.B1#done#0", true);

		plugin.setCompletedBulk(Arrays.asList(step("E1.B1#done#0"), step("E1.B1#never#0")), false);

		assertTrue(autoSuppressed().contains("E1.B1#done#0"));
		assertFalse("a never-completed step keeps its auto-completion",
			autoSuppressed().contains("E1.B1#never#0"));
	}

	@Test
	public void skippingAndUnskippingLeavesNoAutoCompletionVeto()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.setSkipped("E1.B1#s#0", true);
		plugin.setSkipped("E1.B1#s#0", false);

		assertFalse(plugin.isSkipped("E1.B1#s#0"));
		assertFalse(autoSuppressed().contains("E1.B1#s#0"));
	}

	@Test
	public void manualUntickSurvivesARelogButNotACharacterSwitch()
	{
		h.switchCharacter(null, CHARACTER_A);
		plugin.setCompleted("E1.B1#a1#0", true);
		plugin.setCompleted("E1.B1#a1#0", false);
		assertTrue(autoSuppressed().contains("E1.B1#a1#0"));

		h.switchCharacter(CHARACTER_A, null);
		h.switchCharacter(null, CHARACTER_A);
		assertTrue("same character: the untick still wins", autoSuppressed().contains("E1.B1#a1#0"));

		h.switchCharacter(CHARACTER_A, null);
		h.switchCharacter(null, CHARACTER_B);
		assertTrue("another character: manual-control state starts clean", autoSuppressed().isEmpty());
	}

	@Test
	public void previousStepStepsOverACompletedStepThatWasLaterSkipped()
	{
		h.switchCharacter(null, CHARACTER_A);
		h.loadGuide(new String[]{"E1.B1#s1#0", "E1.B1#s2#0", "E1.B1#s3#0"});
		plugin.setCompleted("E1.B1#s1#0", true);
		plugin.setCompleted("E1.B1#s2#0", true);
		plugin.setSkipped("E1.B1#s2#0", true);

		plugin.navigateStep(false);

		assertFalse("the step before the skipped one is reopened", plugin.isCompleted("E1.B1#s1#0"));
		assertTrue("the skipped step keeps its tick", plugin.isCompleted("E1.B1#s2#0"));
	}

	// ------------------------------------------------------------ waypoint index

	@Test
	public void finishingAStepClearsItsStoredWaypointIndex()
	{
		h.switchCharacter(null, CHARACTER_A);
		Map<String, Integer> indexes = h.pluginField("activeWaypointIndexes");
		indexes.put("E1.B1#route#0", 2);
		h.customLocationStore.setActiveIndex(GuideRegistry.BUILTIN_ID, "E1.B1#route#0", 2);

		plugin.setCompleted("E1.B1#route#0", true);

		assertEquals("a step reopened after a restart starts at its first waypoint",
			0, h.customLocationStore.getActiveIndex(GuideRegistry.BUILTIN_ID, "E1.B1#route#0"));
	}

	@Test
	public void storingAWaypointIndexWithoutACharacterProfileDoesNotThrow()
	{
		// the store refuses writes until a character profile exists
		plugin.persistWaypointIndex("E1.B1#route#0", 2);
	}

	// ------------------------------------------------------------ notices and markers

	@Test
	public void bulkChangesDoNotAnnounceASectionButGameplayDoes()
	{
		h.switchCharacter(null, CHARACTER_A);
		h.loadGuide(new String[]{"E1.B1#s1#0"}, new String[]{"E1.B2#s2#0"}, new String[]{"E1.B3#s3#0"});
		Class<?>[] none = new Class<?>[0];
		h.invokePlugin("updateSectionCompleteNotice", none); // baseline tick

		plugin.snapshotBeforeBulk("mark bank complete");
		plugin.setCompletedBulk(Collections.singletonList(step("E1.B1#s1#0")), true);
		h.invokePlugin("updateSectionCompleteNotice", none);
		assertTrue("an administrative bulk change stays silent", h.chatMessages.isEmpty());

		plugin.setCompleted("E1.B2#s2#0", true);
		h.invokePlugin("updateSectionCompleteNotice", none);
		assertEquals(1, h.chatMessages.size());
		assertTrue(h.chatMessages.get(0).contains("Bank 2 complete"));
	}

	@Test
	public void worldMapMarkerComesDownWhenTheDisplayModeStopsAllowingIt()
	{
		Class<?>[] types = {WorldPoint.class, String.class};
		WorldPoint point = new WorldPoint(3200, 3200, 0);
		h.invokePlugin("setMarker", types, point, "Destination");
		assertNotNull(h.pluginField("mapMarker"));

		h.configManager.setConfiguration(GROUP, "guidanceDisplayMode", "SHORTEST_PATH_ONLY");
		h.invokePlugin("setMarker", types, point, "Destination"); // same point, same label

		assertNull(h.pluginField("mapMarker"));
	}

	// ------------------------------------------------------------ teleport bank stock

	@Test
	public void bankedTeleportStockIsPerCharacter()
	{
		TeleportOption.ItemNeed need = null;
		for (TeleportOption option : TeleportDirectory.ALL)
		{
			if (!option.getNeeds().isEmpty())
			{
				need = option.getNeeds().get(0);
				break;
			}
		}
		assertNotNull(need);
		String stock = "{\"" + need.getNamePart() + "\":[7,7]}";
		// a value from before the stock was per-character must not answer for anyone
		h.configManager.setConfiguration(GROUP, "teleportBankStock", stock);
		h.configManager.setConfiguration(GROUP, CHARACTER_A, "teleportBankStock", stock);

		h.switchCharacter(null, CHARACTER_A);
		h.stockTracker.loadBankColumn();
		assertEquals(7, h.stockTracker.banked(need));

		h.switchCharacter(CHARACTER_A, null);
		assertEquals("logging out forgets the previous character's bank", 0, h.stockTracker.banked(need));
		h.stockTracker.saveBankColumn();
		assertEquals("nothing is written while no character is selected",
			stock, h.sharedStore("teleportBankStock"));

		h.switchCharacter(null, CHARACTER_B);
		h.stockTracker.loadBankColumn();
		assertEquals(0, h.stockTracker.banked(need));
	}
}
