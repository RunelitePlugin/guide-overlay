package com.hcimguide;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;

/** Profile-scoped user location overrides and active waypoint indexes. */
@Singleton
final class CustomLocationStore
{
	private static final String GROUP = HcimGuideConfig.GROUP;
	private static final String LOCATION_KEY = "customLocationsV1";
	private static final String INDEX_KEY = "waypointIndexesV1";
	private static final int MAX_JSON_CHARS = 1024 * 1024;
	private static final int MAX_GUIDES = 64;
	private static final int MAX_STEPS = 20_000;
	private static final int MAX_WAYPOINTS = 16;
	private static final Pattern STEP_KEY = Pattern.compile("[A-Za-z0-9._:#-]{3,256}");

	private final Gson gson;
	private final ConfigManager configManager;
	private long revision;

	@Inject
	CustomLocationStore(Gson gson, ConfigManager configManager)
	{
		this.gson = gson;
		this.configManager = configManager;
	}

	synchronized StepLocationPlan getPlan(String guideId, String stepKey)
	{
		Root root = readRoot();
		Map<String, StoredStep> guide = root.guides.get(guideId);
		StoredStep stored = guide == null ? null : guide.get(stepKey);
		if (stored == null || stored.waypoints == null || stored.waypoints.isEmpty())
		{
			return null;
		}
		List<StepLocationHint> hints = new ArrayList<>();
		int index = 0;
		for (StoredWaypoint waypoint : stored.waypoints)
		{
			if (!waypoint.valid() || hints.size() >= MAX_WAYPOINTS)
			{
				continue;
			}
			String id = waypoint.id == null || waypoint.id.trim().isEmpty()
				? "manual:" + guideId + ":" + stepKey + ":" + index : waypoint.id;
			hints.add(StepLocationHint.manual(id,
				waypoint.label == null ? "Custom waypoint " + (index + 1) : waypoint.label,
				new WorldPoint(waypoint.x, waypoint.y, waypoint.plane), waypoint.radius));
			index++;
		}
		return hints.isEmpty() ? null : new StepLocationPlan(stepKey, hints);
	}

	synchronized boolean hasPlan(String guideId, String stepKey)
	{
		Root root = readRoot();
		Map<String, StoredStep> guide = root.guides.get(guideId);
		StoredStep step = guide == null ? null : guide.get(stepKey);
		return step != null && step.waypoints != null && !step.waypoints.isEmpty();
	}

	synchronized void rename(String guideId, String stepKey, int index, String label)
	{
		Root root = readRoot();
		StoredStep step = storedStep(root, guideId, stepKey);
		if (step == null || index < 0 || index >= step.waypoints.size())
		{
			throw new IllegalStateException("The active waypoint is not a custom waypoint");
		}
		String clean = label == null ? "" : label.trim();
		if (clean.isEmpty() || clean.length() > 120)
		{
			throw new IllegalArgumentException("Waypoint label must contain 1-120 characters");
		}
		step.waypoints.get(index).label = clean;
		writeRoot(root);
	}

	synchronized int move(String guideId, String stepKey, int index, int direction)
	{
		Root root = readRoot();
		StoredStep step = storedStep(root, guideId, stepKey);
		if (step == null || index < 0 || index >= step.waypoints.size() || direction == 0)
		{
			throw new IllegalStateException("The active waypoint is not a custom waypoint");
		}
		int target = Math.max(0, Math.min(step.waypoints.size() - 1,
			index + (direction > 0 ? 1 : -1)));
		if (target != index)
		{
			StoredWaypoint waypoint = step.waypoints.remove(index);
			step.waypoints.add(target, waypoint);
			rewriteWaypointIds(guideId, stepKey, step);
			writeRoot(root);
			setActiveIndex(guideId, stepKey, target);
		}
		return target;
	}

	synchronized int removeWaypoint(String guideId, String stepKey, int index)
	{
		Root root = readRoot();
		Map<String, StoredStep> guide = root.guides.get(guideId);
		StoredStep step = guide == null ? null : guide.get(stepKey);
		if (step == null || step.waypoints == null || index < 0 || index >= step.waypoints.size())
		{
			throw new IllegalStateException("The active waypoint is not a custom waypoint");
		}
		step.waypoints.remove(index);
		if (step.waypoints.isEmpty())
		{
			guide.remove(stepKey);
			if (guide.isEmpty())
			{
				root.guides.remove(guideId);
			}
			writeRoot(root);
			removeActiveIndex(guideId, stepKey);
			return 0;
		}
		rewriteWaypointIds(guideId, stepKey, step);
		writeRoot(root);
		int next = Math.min(index, step.waypoints.size() - 1);
		setActiveIndex(guideId, stepKey, next);
		return next;
	}

	private static StoredStep storedStep(Root root, String guideId, String stepKey)
	{
		Map<String, StoredStep> guide = root.guides.get(guideId);
		StoredStep step = guide == null ? null : guide.get(stepKey);
		return step == null || step.waypoints == null ? null : step;
	}

	private static void rewriteWaypointIds(String guideId, String stepKey, StoredStep step)
	{
		for (int i = 0; i < step.waypoints.size(); i++)
		{
			step.waypoints.get(i).id = "manual:" + guideId + ":" + stepKey + ":" + i;
		}
	}

	synchronized void replace(String guideId, String stepKey, String label, WorldPoint point)
	{
		Root root = readRoot();
		StoredStep step = new StoredStep();
		step.waypoints = new ArrayList<>();
		step.waypoints.add(StoredWaypoint.of("manual:" + guideId + ":" + stepKey + ":0",
			label, point, 5));
		root.guides.computeIfAbsent(guideId, k -> new HashMap<>()).put(stepKey, step);
		writeRoot(root);
		setActiveIndex(guideId, stepKey, 0);
	}

	/**
	 * Appends a manual waypoint. When no custom override exists yet, the current
	 * automatic plan is copied first so "Add waypoint" extends the route instead
	 * of silently replacing every automatically resolved stop.
	 */
	synchronized void append(String guideId, String stepKey, StepLocationPlan basePlan,
		String label, WorldPoint point)
	{
		Root root = readRoot();
		Map<String, StoredStep> guide = root.guides.computeIfAbsent(guideId, k -> new HashMap<>());
		StoredStep step = guide.get(stepKey);
		if (step == null)
		{
			if (basePlan != null && basePlan.size() >= MAX_WAYPOINTS)
			{
				throw new IllegalStateException(
					"The automatic route already contains the maximum of " + MAX_WAYPOINTS + " waypoints");
			}
			step = new StoredStep();
			step.waypoints = new ArrayList<>();
			if (basePlan != null)
			{
				for (StepLocationHint waypoint : basePlan.getWaypoints())
				{
					if (waypoint == null || waypoint.getPoint() == null)
					{
						break;
					}
					int index = step.waypoints.size();
					step.waypoints.add(StoredWaypoint.of(
						"manual:" + guideId + ":" + stepKey + ":" + index,
						waypoint.getLabel(), waypoint.getPoint(), waypoint.getArrivalRadius()));
				}
			}
			guide.put(stepKey, step);
		}
		if (step.waypoints == null)
		{
			step.waypoints = new ArrayList<>();
		}
		if (step.waypoints.size() >= MAX_WAYPOINTS)
		{
			throw new IllegalStateException("A step can contain at most " + MAX_WAYPOINTS + " custom waypoints");
		}
		int index = step.waypoints.size();
		step.waypoints.add(StoredWaypoint.of("manual:" + guideId + ":" + stepKey + ":" + index,
			label, point, 5));
		writeRoot(root);
	}

	synchronized void clear(String guideId, String stepKey)
	{
		Root root = readRoot();
		Map<String, StoredStep> guide = root.guides.get(guideId);
		if (guide != null)
		{
			guide.remove(stepKey);
			if (guide.isEmpty())
			{
				root.guides.remove(guideId);
			}
			writeRoot(root);
		}
		removeActiveIndex(guideId, stepKey);
	}

	synchronized String exportGuide(String guideId)
	{
		Root root = readRoot();
		Root export = new Root();
		Map<String, StoredStep> guide = root.guides.get(guideId);
		if (guide != null)
		{
			export.guides.put(guideId, guide);
		}
		return gson.toJson(export);
	}

	synchronized int importJson(String json, String expectedGuideId, Set<String> validStepKeys)
	{
		if (json == null || json.length() > MAX_JSON_CHARS)
		{
			throw new IllegalArgumentException("Custom location data is empty or too large");
		}
		// instance API, not JsonParser.parseString: the client bundles an
		// older gson where the static method does not exist (NoSuchMethodError)
		JsonElement parsed = new JsonParser().parse(json);
		if (!parsed.isJsonObject())
		{
			throw new IllegalArgumentException("Custom location data must be a JSON object");
		}
		Root incoming = gson.fromJson(parsed, Root.class);
		if (incoming == null || incoming.guides == null || incoming.guides.size() > MAX_GUIDES
			|| incoming.schemaVersion < 1 || incoming.schemaVersion > 1)
		{
			throw new IllegalArgumentException("Invalid or unsupported custom location data");
		}
		Root current = readRoot();
		int imported = 0;
		for (Map.Entry<String, Map<String, StoredStep>> guideEntry : incoming.guides.entrySet())
		{
			String guideId = guideEntry.getKey();
			if (guideId == null || !guideId.equals(expectedGuideId) || guideEntry.getValue() == null)
			{
				continue;
			}
			Map<String, StoredStep> destination = current.guides.computeIfAbsent(guideId, k -> new HashMap<>());
			for (Map.Entry<String, StoredStep> stepEntry : guideEntry.getValue().entrySet())
			{
				if (imported >= MAX_STEPS || !validStepKey(stepEntry.getKey())
					|| validStepKeys == null || !validStepKeys.contains(stepEntry.getKey()))
				{
					continue;
				}
				StoredStep sanitized = sanitize(guideId, stepEntry.getKey(), stepEntry.getValue());
				if (sanitized != null)
				{
					destination.put(stepEntry.getKey(), sanitized);
					imported++;
				}
			}
		}
		writeRoot(current);
		return imported;
	}

	synchronized int getActiveIndex(String guideId, String stepKey)
	{
		Map<String, Integer> indexes = readIndexes();
		Integer value = indexes.get(indexKey(guideId, stepKey));
		return value == null ? 0 : Math.max(0, value);
	}

	synchronized void setActiveIndex(String guideId, String stepKey, int index)
	{
		Map<String, Integer> indexes = readIndexes();
		indexes.put(indexKey(guideId, stepKey), Math.max(0, index));
		writeConfig(INDEX_KEY, gson.toJson(indexes));
	}

	private synchronized void removeActiveIndex(String guideId, String stepKey)
	{
		Map<String, Integer> indexes = readIndexes();
		if (indexes.remove(indexKey(guideId, stepKey)) != null)
		{
			writeConfig(INDEX_KEY, gson.toJson(indexes));
		}
	}

	private Root readRoot()
	{
		String json = readConfig(LOCATION_KEY);
		if (json == null || json.isEmpty() || json.length() > MAX_JSON_CHARS)
		{
			return new Root();
		}
		try
		{
			Root root = gson.fromJson(json, Root.class);
			if (root == null || root.guides == null)
			{
				return new Root();
			}
			return root;
		}
		catch (RuntimeException ex)
		{
			return new Root();
		}
	}

	synchronized long getRevision()
	{
		return revision;
	}

	/** Force cached plans to reload after a RuneLite/profile store switch. */
	synchronized void invalidate()
	{
		revision++;
	}

	private void writeRoot(Root root)
	{
		String json = gson.toJson(root);
		if (json.length() > MAX_JSON_CHARS)
		{
			throw new IllegalStateException("Custom location data exceeds the storage limit");
		}
		writeConfig(LOCATION_KEY, json);
		revision++;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Integer> readIndexes()
	{
		String json = readConfig(INDEX_KEY);
		if (json == null || json.length() > MAX_JSON_CHARS)
		{
			return new HashMap<>();
		}
		try
		{
			Map<String, Double> raw = gson.fromJson(json, Map.class);
			Map<String, Integer> out = new HashMap<>();
			if (raw != null)
			{
				for (Map.Entry<String, Double> entry : raw.entrySet())
				{
					if (entry.getKey() != null && entry.getValue() != null)
					{
						out.put(entry.getKey(), entry.getValue().intValue());
					}
				}
			}
			return out;
		}
		catch (RuntimeException ex)
		{
			return new HashMap<>();
		}
	}

	private String readConfig(String key)
	{
		String profile = configManager.getRSProfileConfiguration(GROUP, key);
		return profile != null ? profile : configManager.getConfiguration(GROUP, key);
	}

	private void writeConfig(String key, String value)
	{
		if (configManager.getRSProfileKey() != null)
		{
			configManager.setRSProfileConfiguration(GROUP, key, value);
		}
		else
		{
			configManager.setConfiguration(GROUP, key, value);
		}
	}

	private static String indexKey(String guideId, String stepKey)
	{
		return guideId + "|" + stepKey;
	}

	private static boolean validStepKey(String key)
	{
		return key != null && STEP_KEY.matcher(key).matches();
	}

	private static StoredStep sanitize(String guideId, String stepKey, StoredStep input)
	{
		if (input == null || input.waypoints == null)
		{
			return null;
		}
		StoredStep out = new StoredStep();
		out.waypoints = new ArrayList<>();
		for (StoredWaypoint waypoint : input.waypoints)
		{
			if (waypoint == null || !waypoint.valid() || out.waypoints.size() >= MAX_WAYPOINTS)
			{
				continue;
			}
			String label = waypoint.label == null ? "Custom waypoint" : waypoint.label.trim();
			if (label.isEmpty())
			{
				label = "Custom waypoint";
			}
			if (label.length() > 120)
			{
				label = label.substring(0, 120);
			}
			int index = out.waypoints.size();
			StoredWaypoint clean = StoredWaypoint.of(
				"manual:" + guideId + ":" + stepKey + ":" + index,
				label, new WorldPoint(waypoint.x, waypoint.y, waypoint.plane), waypoint.radius);
			out.waypoints.add(clean);
		}
		return out.waypoints.isEmpty() ? null : out;
	}

	private static final class Root
	{
		private int schemaVersion = 1;
		private Map<String, Map<String, StoredStep>> guides = new HashMap<>();
	}

	private static final class StoredStep
	{
		private List<StoredWaypoint> waypoints = new ArrayList<>();
	}

	private static final class StoredWaypoint
	{
		private String id;
		private String label;
		private int x;
		private int y;
		private int plane;
		private int radius = 5;

		private static StoredWaypoint of(String id, String label, WorldPoint point, int radius)
		{
			StoredWaypoint out = new StoredWaypoint();
			out.id = id;
			out.label = label;
			out.x = point.getX();
			out.y = point.getY();
			out.plane = point.getPlane();
			out.radius = radius;
			return out;
		}

		private boolean valid()
		{
			return x > 0 && x <= 16383 && y > 0 && y <= 16383
				&& plane >= 0 && plane <= 3 && radius >= 1 && radius <= 20;
		}
	}
}
