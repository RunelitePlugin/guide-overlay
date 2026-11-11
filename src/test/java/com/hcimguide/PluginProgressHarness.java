package com.hcimguide;

import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import org.junit.Assume;

/**
 * Test support: the REAL {@link HcimGuidePlugin} progress-store code running
 * against a REAL, in-memory {@link ConfigManager}.
 *
 * <p>The progress rules under test (which store a write lands in, what a
 * character switch reloads, what seeds a new character) are the interaction
 * between the plugin and ConfigManager's shared/RS-profile routing, so a
 * hand-rolled fake of that routing would only test the fake. ConfigManager has
 * no public constructor, so it is built reflectively; if a future RuneLite
 * changes its internals the dependent tests are SKIPPED (JUnit assumption)
 * rather than failing the build.</p>
 *
 * <p>The plugin is never started: its lifecycle generation stays inactive, so
 * every Swing / executor / client-thread hop is dropped and only the
 * synchronous store logic runs.</p>
 */
final class PluginProgressHarness implements AutoCloseable
{
	static final String GROUP = HcimGuideConfig.GROUP;
	static final String PROGRESS_KEY = HcimGuideConfig.COMPLETED_STEPS_KEY + "." + GuideRegistry.BUILTIN_ID;
	static final String SKIPPED_KEY = "skippedSteps." + GuideRegistry.BUILTIN_ID;
	static final String CHARACTER_A = "rsprofile.AAAAAAAA";
	static final String CHARACTER_B = "rsprofile.BBBBBBBB";

	final HcimGuidePlugin plugin;
	final ConfigManager configManager;
	final CustomLocationStore customLocationStore;
	final BankStockTracker stockTracker;
	/** Chat lines the plugin asked the client to print. */
	final List<String> chatMessages = new ArrayList<>();

	private final ScheduledExecutorService executor;
	private volatile GameState gameState = GameState.LOGIN_SCREEN;

	PluginProgressHarness()
	{
		executor = Executors.newSingleThreadScheduledExecutor();
		HcimGuidePlugin builtPlugin = null;
		ConfigManager builtManager = null;
		CustomLocationStore builtStore = null;
		BankStockTracker builtTracker = null;
		try
		{
			Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[]{Client.class}, (proxy, method, args) -> answer(proxy, method, args));

			Constructor<ConfigManager> ctor = ConfigManager.class.getDeclaredConstructor(
				String.class, ScheduledExecutorService.class, EventBus.class, Client.class, Gson.class,
				Class.forName("net.runelite.client.config.ConfigClient"),
				Class.forName("net.runelite.client.config.ProfileManager"),
				Class.forName("net.runelite.client.account.SessionManager"));
			ctor.setAccessible(true);
			Gson gson = new Gson();
			builtManager = ctor.newInstance(null, executor, new EventBus(), client, gson, null, null, null);
			Constructor<?> dataCtor = Class.forName("net.runelite.client.config.ConfigData")
				.getDeclaredConstructor(File.class);
			dataCtor.setAccessible(true);
			File dir = java.nio.file.Files.createTempDirectory("guide-overlay-test").toFile();
			dir.deleteOnExit();
			set(builtManager, ConfigManager.class, "configProfile",
				dataCtor.newInstance(new File(dir, "profile.properties")));
			set(builtManager, ConfigManager.class, "rsProfileConfigProfile",
				dataCtor.newInstance(new File(dir, "rsprofile.properties")));

			builtPlugin = new HcimGuidePlugin();
			builtStore = new CustomLocationStore(gson, builtManager);
			builtTracker = new BankStockTracker((ItemManager) null, builtManager, gson);
			set(builtPlugin, HcimGuidePlugin.class, "client", client);
			set(builtPlugin, HcimGuidePlugin.class, "clientThread", new ClientThread());
			set(builtPlugin, HcimGuidePlugin.class, "configManager", builtManager);
			set(builtPlugin, HcimGuidePlugin.class, "config", builtManager.getConfig(HcimGuideConfig.class));
			set(builtPlugin, HcimGuidePlugin.class, "gson", gson);
			set(builtPlugin, HcimGuidePlugin.class, "executor", executor);
			set(builtPlugin, HcimGuidePlugin.class, "customLocationStore", builtStore);
			set(builtPlugin, HcimGuidePlugin.class, "stockTracker", builtTracker);
			set(builtPlugin, HcimGuidePlugin.class, "worldMapPointManager", new WorldMapPointManager());
			set(builtPlugin, HcimGuidePlugin.class, "locationStore", new NpcLocationStore(gson));
			set(builtPlugin, HcimGuidePlugin.class, "pathfinder", new PathfinderIntegration(message -> { }));
			set(builtPlugin, HcimGuidePlugin.class, "active", true);
		}
		catch (ReflectiveOperationException | RuntimeException | java.io.IOException e)
		{
			executor.shutdownNow();
			Assume.assumeNoException("RuneLite internals this harness builds on have changed", e);
		}
		plugin = builtPlugin;
		configManager = builtManager;
		customLocationStore = builtStore;
		stockTracker = builtTracker;
	}

	private Object answer(Object proxy, Method method, Object[] args)
	{
		switch (method.getName())
		{
			case "getGameState":
				return gameState;
			case "getAccountHash":
				return gameState == GameState.LOGGED_IN ? 1L : -1L;
			case "addChatMessage":
				chatMessages.add(String.valueOf(args[2]));
				return null;
			case "hashCode":
				return System.identityHashCode(proxy);
			case "equals":
				return proxy == args[0];
			case "toString":
				return "client";
			default:
				Class<?> type = method.getReturnType();
				if (type == boolean.class)
				{
					return false;
				}
				if (type == int.class)
				{
					return 0;
				}
				if (type == long.class)
				{
					return 0L;
				}
				return null;
		}
	}

	/**
	 * What ConfigManager does on login/logout: select the RS profile (null =
	 * none, i.e. logged out), then post the change. A logged-in character is
	 * also given the profile metadata every real RS profile carries.
	 */
	void switchCharacter(String previous, String next)
	{
		gameState = next == null ? GameState.LOGIN_SCREEN : GameState.LOGGED_IN;
		if (next != null)
		{
			configManager.setConfiguration("rsprofile", next, "displayName", next.substring(next.length() - 1));
		}
		field(configManager, ConfigManager.class, "rsProfileKey", next);
		plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged(previous, next));
		fireGameState(gameState);
	}

	/** Drop the selected RS profile WITHOUT an event or a game-state change (login-order window). */
	void setProfileKeyOnly(String key)
	{
		field(configManager, ConfigManager.class, "rsProfileKey", key);
	}

	void fireGameState(GameState state)
	{
		gameState = state;
		GameStateChanged event = new GameStateChanged();
		event.setGameState(state);
		plugin.onGameStateChanged(event);
	}

	String sharedStore(String key)
	{
		return configManager.getConfiguration(GROUP, key);
	}

	String characterStore(String character, String key)
	{
		return configManager.getConfiguration(GROUP, character, key);
	}

	void setPluginField(String name, Object value)
	{
		field(plugin, HcimGuidePlugin.class, name, value);
	}

	@SuppressWarnings("unchecked")
	<T> T pluginField(String name)
	{
		try
		{
			Field f = HcimGuidePlugin.class.getDeclaredField(name);
			f.setAccessible(true);
			return (T) f.get(plugin);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	Object invokePlugin(String method, Class<?>[] types, Object... args)
	{
		try
		{
			Method m = HcimGuidePlugin.class.getDeclaredMethod(method, types);
			m.setAccessible(true);
			return m.invoke(plugin, args);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	/** One episode of banks, each bank given as its step keys; installed as the loaded guide. */
	Guide loadGuide(String[]... banks)
	{
		Guide guide = new Guide();
		GuideEpisode episode = new GuideEpisode(1, "Episode 1");
		for (int b = 0; b < banks.length; b++)
		{
			String bankId = "E1.B" + (b + 1);
			GuideBank bank = new GuideBank(bankId, "Bank " + (b + 1), 1);
			for (String key : banks[b])
			{
				bank.getSteps().add(new GuideStep(key, "step " + key, 0, bankId));
			}
			episode.getBanks().add(bank);
		}
		guide.getEpisodes().add(episode);
		setPluginField("currentGuide", guide);
		return guide;
	}

	private static void field(Object target, Class<?> owner, String name, Object value)
	{
		try
		{
			set(target, owner, name, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	private static void set(Object target, Class<?> owner, String name, Object value)
		throws ReflectiveOperationException
	{
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}

	@Override
	public void close()
	{
		executor.shutdownNow();
	}
}
