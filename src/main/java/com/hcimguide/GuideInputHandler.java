package com.hcimguide;

import java.awt.Point;
import java.util.Objects;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.util.HotkeyListener;

/**
 * All keyboard and mouse handling for the guide's step navigation.
 *
 * <p>This is deliberately the ONLY class in the plugin that touches RuneLite's
 * input API. Keeping it separate means the rest of the plugin can be seen, at a
 * glance, to have no way of producing input at all: no other file imports
 * {@link KeyManager} or {@link MouseManager}.</p>
 *
 * <p>Nothing here generates input. Both listeners are read-only observers that
 * consume an event when the click landed on one of the guide's own on-screen
 * arrows, so the click does not also reach the game. No event is ever
 * synthesised, injected or replayed.</p>
 */
@Singleton
public class GuideInputHandler
{
	/**
	 * What the handler is allowed to ask the plugin to do. Deliberately narrow:
	 * two navigation actions and two hit tests, nothing else.
	 */
	public interface Actions
	{
		/** Move to the next ({@code true}) or previous step. */
		void navigateStep(boolean forward);

		/** Show or hide location guidance for the current step. */
		void toggleLocationGuide();

		/** Direction of the on-screen step arrow at {@code p}, or 0 for none. */
		int hitNavArrow(Point p);

		/** True when {@code p} is on the location-guidance toggle. */
		boolean hitLocationToggle(Point p);
	}

	private final HcimGuideConfig config;
	private final KeyManager keyManager;
	private final MouseManager mouseManager;

	/*
	 * Threading: register and unregister run on the client thread, while the
	 * listeners below run on the AWT event thread. The gesture fields are
	 * therefore written from both, so they are volatile for visibility. The
	 * synchronized keyword on register/unregister guards the registration
	 * sequence itself and the `registered` flag; it does NOT publish these
	 * fields to the listener thread, which is why volatile is needed as well.
	 * Every read-modify-write of the gesture state happens on the event thread
	 * alone, so visibility is sufficient and no atomicity is required.
	 */

	/** Set while a consumed press is awaiting its matching left-button release. */
	private volatile boolean navPressConsumed;

	/**
	 * Set after a guide control consumed the press. MouseClicked is delivered
	 * after mouseReleased; by then the action may have hidden or moved the
	 * control, so re-running hit tests is not sufficient to keep the same
	 * gesture from reaching the game.
	 */
	private volatile boolean navClickPending;
	private volatile long navReleaseWhen;

	/** Only touched inside the synchronized lifecycle methods. */
	private boolean registered;

	private volatile Actions actions;

	@Inject
	GuideInputHandler(HcimGuideConfig config, KeyManager keyManager, MouseManager mouseManager)
	{
		this.config = config;
		this.keyManager = keyManager;
		this.mouseManager = mouseManager;
		this.nextStepHotkey = new HotkeyListener(() -> this.config.nextStepKeybind())
		{
			@Override
			public void hotkeyPressed()
			{
				if (GuideInputHandler.this.actions != null && GuideInputHandler.this.config.navKeybindsEnabled())
				{
					GuideInputHandler.this.actions.navigateStep(true);
				}
			}
		};
		this.prevStepHotkey = new HotkeyListener(() -> this.config.prevStepKeybind())
		{
			@Override
			public void hotkeyPressed()
			{
				if (GuideInputHandler.this.actions != null && GuideInputHandler.this.config.navKeybindsEnabled())
				{
					GuideInputHandler.this.actions.navigateStep(false);
				}
			}
		};
	}

	private final HotkeyListener nextStepHotkey;
	private final HotkeyListener prevStepHotkey;

	private void markConsumedPress(MouseEvent e)
	{
		navPressConsumed = true;
		navClickPending = true;
		navReleaseWhen = 0L;
	}

	private boolean isMatchingPendingClick(MouseEvent e)
	{
		if (!navClickPending || e.getButton() != MouseEvent.BUTTON1)
		{
			return false;
		}

		// A normal AWT click follows its release immediately, so a short time
		// bound is enough to stop a drag with no click event from causing an
		// unrelated later click to be consumed. Deliberately NO spatial bound:
		// AWT still synthesizes a click after well over six pixels of movement
		// on the same canvas, and the control action taken on press may have
		// moved or cleared the hitboxes - the captured gesture must be
		// consumed wherever its click lands, or it falls through to the game.
		return navReleaseWhen == 0L
			|| (e.getWhen() >= navReleaseWhen && e.getWhen() - navReleaseWhen <= 1500L);
	}

	private void clearPendingClick()
	{
		navClickPending = false;
		navReleaseWhen = 0L;
	}

	private final MouseAdapter navMouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			// Alt+drag is overlay repositioning - never intercept it. It still
			// STARTS a new gesture, so a stale click-suppression from a
			// previous capture must not survive to eat the Alt gesture's click.
			if (actions == null || e.getButton() != MouseEvent.BUTTON1 || e.isAltDown())
			{
				clearPendingClick();
				return e;
			}
			// A NEW press starts a NEW gesture: a captured gesture whose click
			// event never fired (AWT suppresses clicks after large drags) must
			// not survive to eat this unrelated gesture's click.
			clearPendingClick();
			if (actions.hitLocationToggle(e.getPoint()))
			{
				markConsumedPress(e);
				e.consume();
				actions.toggleLocationGuide();
				return e;
			}
			int dir = actions.hitNavArrow(e.getPoint());
			if (dir != 0)
			{
				markConsumedPress(e);
				e.consume();
				actions.navigateStep(dir > 0);
			}
			else
			{
				// stuck-capture heal: if a captured gesture's release was never
				// delivered (focus loss mid-press), this non-control gesture
				// must not have its drags and release consumed by it
				navPressConsumed = false;
			}
			return e;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent e)
		{
			// the captured gesture owns its drag events too - without this a
			// press-move-release on a control leaks the drags to the game
			if (navPressConsumed)
			{
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			// consume the captured gesture's release REGARDLESS of where the
			// cursor is now - no re-hit-testing against moved hitboxes
			if (navPressConsumed && e.getButton() == MouseEvent.BUTTON1)
			{
				navPressConsumed = false;
				navReleaseWhen = e.getWhen();
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent e)
		{
			// Consume the click belonging to a press we already handled, even if
			// that action changed the overlay so the control no longer hit-tests.
			if (isMatchingPendingClick(e))
			{
				clearPendingClick();
				e.consume();
				return e;
			}

			// Any non-matching click ends the stale gesture. Keep the old hit-test
			// fallback for environments that deliver clicked without pressed.
			clearPendingClick();
			if (actions != null
				&& e.getButton() == MouseEvent.BUTTON1 && !e.isAltDown()
				&& (actions.hitLocationToggle(e.getPoint())
					|| actions.hitNavArrow(e.getPoint()) != 0))
			{
				e.consume();
			}
			return e;
		}
	};

	/** Start listening. Called from the plugin's startUp. */
	public synchronized void register(Actions actions)
	{
		this.actions = Objects.requireNonNull(actions, "actions");
		if (registered)
		{
			return;
		}

		boolean nextRegistered = false;
		boolean prevRegistered = false;
		try
		{
			keyManager.registerKeyListener(nextStepHotkey);
			nextRegistered = true;
			keyManager.registerKeyListener(prevStepHotkey);
			prevRegistered = true;
			mouseManager.registerMouseListener(navMouse);
			registered = true;
		}
		catch (RuntimeException ex)
		{
			if (prevRegistered)
			{
				keyManager.unregisterKeyListener(prevStepHotkey);
			}
			if (nextRegistered)
			{
				keyManager.unregisterKeyListener(nextStepHotkey);
			}
			this.actions = null;
			throw ex;
		}
	}

	/** Stop listening and drop the callback. Called from the plugin's shutDown. */
	public synchronized void unregister()
	{
		if (registered)
		{
			// Remove the mouse listener first so no new gesture can begin while the
			// key listeners and callback are being torn down.
			mouseManager.unregisterMouseListener(navMouse);
			keyManager.unregisterKeyListener(prevStepHotkey);
			keyManager.unregisterKeyListener(nextStepHotkey);
			registered = false;
		}
		actions = null;
		navPressConsumed = false;
		clearPendingClick();
	}
}
