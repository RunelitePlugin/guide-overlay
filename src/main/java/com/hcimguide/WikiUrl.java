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

		if (s.contains("://") || s.startsWith("www."))
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
		s = s.trim().replace(' ', '_');
		if (s.isEmpty() || s.length() > 255)
		{
			throw new IllegalArgumentException("Not a valid wiki page title");
		}
		return s;
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
