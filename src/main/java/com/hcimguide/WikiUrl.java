package com.hcimguide;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a pasted OSRS-wiki link into a page title. Only the official wiki
 * host is accepted, so a guide can never be imported from an arbitrary
 * website. Pure Java - unit-testable anywhere.
 */
public final class WikiUrl
{
	private static final Pattern URL = Pattern.compile(
		"^https?://oldschool\\.runescape\\.wiki/(?:w|wiki)/([^?#]+).*$", Pattern.CASE_INSENSITIVE);

	private WikiUrl()
	{
	}

	/**
	 * @param input a wiki URL (https://oldschool.runescape.wiki/w/Page_Title)
	 *              or a bare page title
	 * @return the wiki page title, underscores preserved
	 * @throws IllegalArgumentException when the input is not an OSRS-wiki link or title
	 */
	public static String pageTitle(String input)
	{
		if (input == null || input.trim().isEmpty())
		{
			throw new IllegalArgumentException("No link given");
		}
		String s = input.trim();

		boolean link = s.contains("://") || s.startsWith("www.");
		if (link)
		{
			Matcher m = URL.matcher(s.startsWith("www.") ? "https://" + s.substring(4) : s);
			if (!m.matches())
			{
				throw new IllegalArgumentException(
					"Only oldschool.runescape.wiki links are supported");
			}
			s = m.group(1);
		}
		try
		{
			// in a URL PATH a literal "+" means "+", but URLDecoder applies
			// query semantics ("+" -> space) - protect it first
			s = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			// UTF-8 is always present; fall through with the raw value
		}
		catch (IllegalArgumentException e)
		{
			// a malformed escape: an error in a link, but a bare title may hold
			// a literal '%' ("100%_run") and is then kept exactly as typed
			if (link)
			{
				throw new IllegalArgumentException("Not a valid wiki link");
			}
		}
		s = s.trim().replace(' ', '_');
		if (!isValidTitle(s))
		{
			throw new IllegalArgumentException("Not a valid wiki page title");
		}
		return s;
	}

	/**
	 * True for a title exactly as {@link #pageTitle} returns it. Deliberately
	 * NON-decoding: a stored title is already decoded, so it may legitimately
	 * contain '%' ("100%_run", "A%41") and must validate as the same string it
	 * was stored as - decoding it again would reject or silently change it.
	 */
	static boolean isValidTitle(String s)
	{
		// MediaWiki forbids these in titles; for us they're also the Swing-HTML
		// injection characters - reject outright
		return s != null && !s.isEmpty() && s.length() <= 255 && s.equals(s.trim())
			&& s.indexOf(' ') < 0 && !s.contains("<") && !s.contains(">")
			&& !s.contains("[") && !s.contains("]") && !s.contains("{") && !s.contains("}")
			&& !s.contains("|");
	}

	/** Human-friendly display name for a page title ("Guide:Some_Guide" -> "Some Guide"). */
	public static String displayName(String pageTitle)
	{
		String s = pageTitle.replace('_', ' ');
		int colon = s.indexOf(':');
		if (colon > 0 && colon < s.length() - 1)
		{
			s = s.substring(colon + 1);
		}
		return s.trim();
	}
}
