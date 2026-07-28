package com.hcimguide;

/**
 * Step-scoped location-guide suppression.
 *
 * A hide applies to exactly the step it was made on. When the guided step
 * moves on, the suppression clears - the next step gets its arrow even when
 * its destination is the same building. (An earlier design carried
 * suppression into "equivalent" follow-up destinations while the player
 * stayed put; in practice that read as arrows going randomly missing, so
 * suppression is now strictly per-step.)
 */
final class LocationSuppressionState
{
	private String originStepKey;

	synchronized void hide(String stepKey)
	{
		if (stepKey != null)
		{
			originStepKey = stepKey;
		}
	}

	synchronized void clear()
	{
		originStepKey = null;
	}

	synchronized boolean isActive()
	{
		return originStepKey != null;
	}

	synchronized boolean hides(String stepKey)
	{
		return originStepKey != null && originStepKey.equals(stepKey);
	}

	/**
	 * Clears the suppression once the guided step is no longer the origin.
	 * Returns true when state changed (so callers can mark routes dirty).
	 */
	synchronized boolean clearWhenGuidedStepChanged(String guidedStepKey)
	{
		if (originStepKey != null && !originStepKey.equals(guidedStepKey))
		{
			originStepKey = null;
			return true;
		}
		return false;
	}
}
