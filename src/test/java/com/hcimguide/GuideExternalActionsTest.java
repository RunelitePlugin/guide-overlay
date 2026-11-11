package com.hcimguide;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the external actions boundary.
 *
 * <p>The browser is stubbed through {@link GuideExternalActions.BrowserLauncher}
 * so these tests can assert the exact URL that would be opened, and assert that
 * nothing is opened at all for a rejected URL, without launching a browser.</p>
 */
public class GuideExternalActionsTest
{
	private List<String> opened;
	private GuideExternalActions actions;

	@Before
	public void setUp()
	{
		opened = new ArrayList<>();
		actions = new GuideExternalActions(opened::add);
	}

	@Test
	public void acceptsApprovedYoutubeUrl()
	{
		assertTrue(actions.openVideoUrl("https://www.youtube.com/watch?v=aBcD"));
		assertEquals(1, opened.size());
		assertEquals("https://www.youtube.com/watch?v=aBcD", opened.get(0));
	}

	@Test
	public void acceptsApprovedShortUrl()
	{
		assertTrue(actions.openVideoUrl("https://youtu.be/abc123"));
		assertEquals("https://youtu.be/abc123", opened.get(0));
	}

	/**
	 * The reason this boundary calls {@link VideoLinks#accepted} rather than the
	 * boolean test. Validating and then opening the original string would hand
	 * the browser a downgraded connection for a guide containing a plain http
	 * link.
	 */
	@Test
	public void upgradesHttpToHttpsBeforeOpening()
	{
		assertTrue(actions.openVideoUrl("http://youtu.be/abc123"));
		assertEquals("https://youtu.be/abc123", opened.get(0));
	}

	/** Video ids are case sensitive, so only the scheme may be rewritten. */
	@Test
	public void preservesVideoIdCaseWhileUpgradingScheme()
	{
		assertTrue(actions.openVideoUrl("HTTP://youtu.be/CaseKept"));
		assertEquals("https://youtu.be/CaseKept", opened.get(0));
	}

	@Test
	public void rejectsJavascriptScheme()
	{
		assertFalse(actions.openVideoUrl("javascript:alert(1)"));
		assertTrue(opened.isEmpty());
	}

	@Test
	public void rejectsFileScheme()
	{
		assertFalse(actions.openVideoUrl("file:///etc/passwd"));
		assertTrue(opened.isEmpty());
	}

	@Test
	public void rejectsUnapprovedHost()
	{
		assertFalse(actions.openVideoUrl("https://evil.example.com/x"));
		assertTrue(opened.isEmpty());
	}

	/** "youtu.be.evil.com" must not pass as "youtu.be". */
	@Test
	public void rejectsHostSuffixAttack()
	{
		assertFalse(actions.openVideoUrl("https://youtu.be.evil.com/x"));
		assertTrue(opened.isEmpty());
	}

	/** "https://youtube.com@evil.example/" resolves to evil.example. */
	@Test
	public void rejectsUserinfoAttack()
	{
		assertFalse(actions.openVideoUrl("https://youtube.com@evil.example/"));
		assertTrue(opened.isEmpty());
	}

	@Test
	public void rejectsNull()
	{
		assertFalse(actions.openVideoUrl(null));
		assertTrue(opened.isEmpty());
	}

	@Test
	public void neverInvokesBrowserForAnyRejectedUrl()
	{
		String[] rejected = {
			"javascript:alert(1)",
			"file:///etc/passwd",
			"https://evil.example.com/x",
			"https://youtu.be.evil.com/x",
			"https://youtube.com@evil.example/",
			"ftp://youtu.be/abc",
			"https://youtu.be/abc def",
			null,
		};
		for (String url : rejected)
		{
			assertFalse("should reject: " + url, actions.openVideoUrl(url));
		}
		assertTrue("browser must never be invoked for a rejected URL", opened.isEmpty());
	}

	@Test
	public void neverAcceptsAStringTheBrowserLauncherCannotParse()
	{
		// LinkBrowser parses its argument with java.net.URI on the calling thread
		String[] notUris = {
			"https://youtu.be/abc\"def",
			"https://youtu.be/a|b",
			"https://youtu.be/a^b",
			"https://youtu.be/a%zz",
			"https://youtube.com/watch?v={x}",
			"https://youtu.be/a`b",
			"https://youtube.com:port/watch",
		};
		for (String url : notUris)
		{
			assertFalse("should reject: " + url, actions.openVideoUrl(url));
			assertEquals("must not be discovered in step text: " + url,
				null, VideoLinks.firstVideoUrl("Watch " + url + " first"));
		}
		assertTrue(opened.isEmpty());
	}

	@Test
	public void everyAcceptedUrlIsAValidUri()
	{
		String[] accepted = {
			"https://www.youtube.com/watch?v=aBcD&t=10s",
			"http://m.youtube.com/watch?v=aBcD#t=1",
			"https://youtu.be/abc-_123",
			"https://streamable.com/x1y2z3",
			"https://youtube.com:443/watch?v=a%20b",
		};
		for (String url : accepted)
		{
			String ok = VideoLinks.accepted(url);
			assertTrue("should accept: " + url, ok != null);
			java.net.URI.create(ok); // throws on anything the launcher would refuse
		}
	}

	@Test
	public void aFailingLauncherMeansFalseNotAnException()
	{
		GuideExternalActions failing = new GuideExternalActions(url ->
		{
			throw new IllegalArgumentException("launcher refused " + url);
		});
		assertFalse(failing.openVideoUrl("https://youtu.be/abc123"));
	}
}
