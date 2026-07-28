package com.hcimguide;

import java.util.Locale;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;

/** One destination/waypoint for a guide step. */
final class StepLocationHint
{
	// precompiled per the convention documented in Names: identity generation
	// runs once per hint, and every plan rebuild constructs many hints
	private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
	private static final Pattern SLUG_EDGE_DASH = Pattern.compile("(?:^-+|-+$)");

	private final String label;
	private final WorldPoint point;
	private final boolean inferred;
	private final boolean preferredOverEntity;
	private final boolean transport;
	private final String identity;
	private final LocationSource source;
	private final LocationConfidence confidence;
	/** Arrival radius for a plain teleport that lands on the objective. */
	private static final int DEFAULT_TRANSPORT_RADIUS = 3;
	/** Wider radius for transports that drop the player at a fixed structure. */
	static final int STRUCTURE_TRANSPORT_RADIUS = 8;

	private final int arrivalRadius;
	private final int confirmationTicks;

	StepLocationHint(String label, WorldPoint point, boolean inferred)
	{
		this(label, point, inferred, false, false);
	}

	StepLocationHint(String label, WorldPoint point, boolean inferred, boolean preferredOverEntity)
	{
		this(label, point, inferred, preferredOverEntity, false);
	}

	StepLocationHint(String label, WorldPoint point, boolean inferred,
		boolean preferredOverEntity, boolean transport)
	{
		this(label, point, inferred, preferredOverEntity, transport,
			defaultIdentity(label, point, transport),
			inferred ? LocationSource.INHERITED_CONTEXT
				: transport ? LocationSource.TRANSPORT : LocationSource.NAMED_PLACE,
			inferred ? LocationConfidence.MEDIUM : LocationConfidence.HIGH,
			transport ? 4 : 5, 2);
	}

	StepLocationHint(String label, WorldPoint point, boolean inferred,
		boolean preferredOverEntity, boolean transport, String identity,
		LocationSource source, LocationConfidence confidence,
		int arrivalRadius, int confirmationTicks)
	{
		this.label = label == null ? "Destination" : label;
		this.point = point;
		this.inferred = inferred;
		this.preferredOverEntity = preferredOverEntity;
		this.transport = transport;
		this.identity = identity == null || identity.isEmpty()
			? defaultIdentity(this.label, point, transport) : identity;
		this.source = source == null ? LocationSource.NAMED_PLACE : source;
		this.confidence = confidence == null ? LocationConfidence.HIGH : confidence;
		this.arrivalRadius = Math.max(1, Math.min(20, arrivalRadius));
		this.confirmationTicks = Math.max(1, Math.min(10, confirmationTicks));
	}

	static StepLocationHint manual(String identity, String label, WorldPoint point, int radius)
	{
		return new StepLocationHint(label, point, false, true, false, identity,
			LocationSource.USER_PIN, LocationConfidence.MANUAL, radius, 2);
	}

	String getLabel()
	{
		return label;
	}

	WorldPoint getPoint()
	{
		return point;
	}

	boolean isInferred()
	{
		return inferred;
	}

	boolean isPreferredOverEntity()
	{
		return preferredOverEntity;
	}

	boolean isTransport()
	{
		return transport;
	}

	String getIdentity()
	{
		return identity;
	}

	LocationSource getSource()
	{
		return source;
	}

	LocationConfidence getConfidence()
	{
		return confidence;
	}

	int getArrivalRadius()
	{
		return arrivalRadius;
	}

	int getConfirmationTicks()
	{
		return confirmationTicks;
	}

	StepLocationHint asTransport(boolean preferOverEntity)
	{
		return asTransport(preferOverEntity, DEFAULT_TRANSPORT_RADIUS);
	}

	/**
	 * Transport waypoint with an explicit arrival radius. Structure-drop
	 * transports (spirit tree, gnome glider, fairy ring, minecart) deposit the
	 * player at a fixed node that can be several tiles from the step's real
	 * objective, so the default tight radius never registers arrival and
	 * auto-advance silently fails. The planner passes a wider radius for those.
	 */
	StepLocationHint asTransport(boolean preferOverEntity, int transportRadius)
	{
		int r = Math.max(transportRadius, arrivalRadius);
		return transport && preferredOverEntity == preferOverEntity && arrivalRadius >= r ? this
			: new StepLocationHint(label, point, inferred, preferOverEntity, true,
				identity.startsWith("transport:") ? identity : "transport:" + identity,
				LocationSource.TRANSPORT, confidence, r, confirmationTicks);
	}

	StepLocationHint inferredCopy()
	{
		return inferred ? this : new StepLocationHint(label, point, true, false, transport,
			identity, LocationSource.INHERITED_CONTEXT, LocationConfidence.MEDIUM,
			arrivalRadius, confirmationTicks);
	}

	StepLocationHint withPreferredOverEntity(boolean preferOverEntity)
	{
		return preferredOverEntity == preferOverEntity ? this
			: new StepLocationHint(label, point, inferred, preferOverEntity, transport,
				identity, source, confidence, arrivalRadius, confirmationTicks);
	}

	StepLocationHint withSource(LocationSource newSource, LocationConfidence newConfidence)
	{
		return new StepLocationHint(label, point, inferred, preferredOverEntity, transport,
			identity, newSource, newConfidence, arrivalRadius, confirmationTicks);
	}

	boolean equivalentTo(StepLocationHint other, int radius)
	{
		if (other == null)
		{
			return false;
		}
		if (identity.equals(other.identity))
		{
			return true;
		}
		return point != null && other.point != null && point.getPlane() == other.point.getPlane()
			&& Math.max(Math.abs(point.getX() - other.point.getX()),
				Math.abs(point.getY() - other.point.getY())) <= Math.max(0, radius);
	}

	private static String defaultIdentity(String label, WorldPoint point, boolean transport)
	{
		String base = label == null ? "destination"
			: SLUG_EDGE_DASH.matcher(
				NON_SLUG.matcher(label.toLowerCase(Locale.ROOT)).replaceAll("-")).replaceAll("");
		String prefix = transport ? "transport:" : "place:";
		if (!base.isEmpty())
		{
			return prefix + base;
		}
		return prefix + (point == null ? "unknown"
			: point.getX() + ":" + point.getY() + ":" + point.getPlane());
	}
}
