package com.hcimguide;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.util.LinkBrowser;

/**
 * Every clipboard read or write, and every external browser launch, in one
 * place.
 *
 * <p>This is deliberately the ONLY class in the plugin that reads or writes the
 * system clipboard, or asks the operating system to open a URL. No other file
 * imports {@link Toolkit}, {@link java.awt.datatransfer.Clipboard},
 * {@link StringSelection} or {@link LinkBrowser}.</p>
 *
 * <p>The boundary is deliberately narrow. It does NOT cover every desktop-facing
 * thing the plugin does: Swing rendering and the file chooser used for guide
 * import and export are still in the panel. The verifiable claim is about the
 * clipboard and the browser only.</p>
 *
 * <p>The clipboard is only ever read when the user explicitly asks to import
 * something, and only ever written when the user explicitly asks to export. The
 * plugin never polls it.</p>
 *
 * <p>URL opening is validated here rather than at the call sites. Guide content
 * is remote data, so a video link arrives from the same untrusted source as the
 * rest of a guide. {@link VideoLinks#isVideoUrl} already rejects anything that
 * is not an http(s) URL on an exact host allowlist, but before this class that
 * check ran when the guide was parsed while the browser call happened much
 * later, in a different file. Re-checking at the moment of opening turns a
 * property that depended on an upstream invariant into one that can be verified
 * by reading this method.</p>
 */
@Singleton
public class GuideExternalActions
{
	/**
	 * Indirection over the browser so a test can assert exactly which URL would
	 * be opened, and assert that nothing is opened for a rejected URL, without
	 * launching a real browser.
	 */
	public interface BrowserLauncher
	{
		void browse(String url);
	}

	private final BrowserLauncher launcher;

	@Inject
	GuideExternalActions()
	{
		this(LinkBrowser::browse);
	}

	GuideExternalActions(BrowserLauncher launcher)
	{
		this.launcher = launcher;
	}

	/**
	 * Put {@code text} on the system clipboard.
	 *
	 * @throws RuntimeException if the clipboard is unavailable, which AWT does
	 *     when another application holds it. Callers surface the message.
	 */
	public void copyText(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new StringSelection(text == null ? "" : text), null);
	}

	/**
	 * Current clipboard contents as text, or null when the clipboard holds
	 * something else or cannot be read. Never throws.
	 */
	public String readText()
	{
		try
		{
			return (String) Toolkit.getDefaultToolkit().getSystemClipboard()
				.getData(DataFlavor.stringFlavor);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	/**
	 * Open a guide video link in the user's browser, but only the canonical
	 * form of a URL that passes every check.
	 *
	 * <p>Uses {@link VideoLinks#accepted} rather than the boolean test, because
	 * {@code accepted} also upgrades an {@code http://} link to {@code https://}.
	 * Validating with the boolean form and then opening the ORIGINAL string
	 * would hand the browser a downgraded connection for a guide that contains a
	 * plain http link, which is exactly what that normalization exists to
	 * prevent. Opening the returned value keeps every URL property at this
	 * boundary: allowlist, scheme, https upgrade, and null rejection.</p>
	 *
	 * @return true if the URL was accepted and handed to the browser. A false
	 *     return means nothing was opened.
	 */
	public boolean openVideoUrl(String url)
	{
		String accepted = VideoLinks.accepted(url);
		if (accepted == null)
		{
			return false;
		}
		launcher.browse(accepted);
		return true;
	}
}
