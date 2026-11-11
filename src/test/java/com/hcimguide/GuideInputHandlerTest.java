package com.hcimguide;

import java.awt.Component;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import javax.swing.JPanel;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.util.HotkeyListener;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuideInputHandlerTest
{
	private static final Point ON_ARROW = new Point(10, 10);
	private static final Point ELSEWHERE = new Point(200, 200);

	private final Component canvas = new JPanel();
	private boolean keybindsEnabled;
	private int navigations;
	private GuideInputHandler handler;
	private HotkeyListener nextHotkey;
	private MouseAdapter mouse;

	@Before
	public void setUp() throws Exception
	{
		HcimGuideConfig config = new HcimGuideConfig()
		{
			@Override
			public boolean navKeybindsEnabled()
			{
				return keybindsEnabled;
			}

			@Override
			public Keybind nextStepKeybind()
			{
				return new Keybind(KeyEvent.VK_F5, 0);
			}
		};
		// the managers are only used by register/unregister, which these tests
		// bypass: the listeners are driven directly, exactly as RuneLite would
		handler = new GuideInputHandler(config, null, null);
		setField("actions", new GuideInputHandler.Actions()
		{
			@Override
			public void navigateStep(boolean forward)
			{
				navigations++;
			}

			@Override
			public void toggleLocationGuide()
			{
			}

			@Override
			public int hitNavArrow(Point p)
			{
				return ON_ARROW.equals(p) ? 1 : 0;
			}

			@Override
			public boolean hitLocationToggle(Point p)
			{
				return false;
			}
		});
		nextHotkey = (HotkeyListener) field("nextStepHotkey").get(handler);
		mouse = (MouseAdapter) field("navMouse").get(handler);
	}

	private Field field(String name) throws Exception
	{
		Field f = GuideInputHandler.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private void setField(String name, Object value) throws Exception
	{
		field(name).set(handler, value);
	}

	private KeyEvent f5()
	{
		// a hand-built event carries no extended key code, which is what
		// Keybind matches on - supply the one a real F5 press reports
		return new KeyEvent(canvas, KeyEvent.KEY_PRESSED, 1L, 0, KeyEvent.VK_F5, KeyEvent.CHAR_UNDEFINED)
		{
			@Override
			public int getExtendedKeyCode()
			{
				return KeyEvent.VK_F5;
			}
		};
	}

	private MouseEvent mouseEvent(int id, Point p, int button)
	{
		return new MouseEvent(canvas, id, 1L, 0, p.x, p.y, 1, false, button);
	}

	@Test
	public void aBoundKeyIsLeftAloneWhileKeybindsAreSwitchedOff()
	{
		keybindsEnabled = false;
		KeyEvent press = f5();

		nextHotkey.keyPressed(press);

		assertFalse("the key must still reach the game and other plugins", press.isConsumed());
		assertEquals(0, navigations);
	}

	@Test
	public void aBoundKeyNavigatesAndIsConsumedWhileKeybindsAreOn()
	{
		keybindsEnabled = true;
		KeyEvent press = f5();

		nextHotkey.keyPressed(press);

		assertTrue(press.isConsumed());
		assertEquals(1, navigations);
	}

	@Test
	public void aCaptureWhoseReleaseNeverCameDoesNotEatAnotherButtonsDrag()
	{
		MouseEvent press = mouseEvent(MouseEvent.MOUSE_PRESSED, ON_ARROW, MouseEvent.BUTTON1);
		mouse.mousePressed(press);
		assertTrue(press.isConsumed());
		// ...the release is lost; the next gesture is a right-button drag
		mouse.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, ELSEWHERE, MouseEvent.BUTTON3));
		MouseEvent drag = mouseEvent(MouseEvent.MOUSE_DRAGGED, ELSEWHERE, MouseEvent.BUTTON3);

		mouse.mouseDragged(drag);

		assertFalse(drag.isConsumed());
	}

	@Test
	public void losingFocusDropsACapturedPress()
	{
		mouse.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, ON_ARROW, MouseEvent.BUTTON1));

		nextHotkey.focusLost();

		MouseEvent drag = mouseEvent(MouseEvent.MOUSE_DRAGGED, ELSEWHERE, MouseEvent.BUTTON1);
		mouse.mouseDragged(drag);
		assertFalse(drag.isConsumed());
	}
}
