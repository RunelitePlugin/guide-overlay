package com.hcimguide;

import java.util.Locale;

/**
 * Recognition of the screenshot links a wiki guide embeds with
 * {@code {{Extimage|url|...}}} (the B0aty guide shows each bank's inventory
 * setup this way).
 *
 * <p>Guide text is REMOTE, publicly editable data, so a URL only qualifies when
 * every part of it is the plain shape the image host actually serves: HTTPS,
 * the one exact host, no userinfo, port, query or fragment, and a two-segment
 * path ending in an image extension. Anything else is simply not a screenshot
 * as far as the plugin is concerned - it is neither shown as a link nor ever
 * requested.</p>
 */
final class GuideImageLinks
{
	/** The only host a guide screenshot may live on. */
	static final String HOST = "i.ibb.co";
	/** Screenshots kept per section; the guide never has more than two. */
	static final int MAX_PER_BANK = 4;

	private static final String PREFIX = "https://" + HOST + "/";
	private static final String TEMPLATE = "{{extimage|";
	private static final int MAX_URL = 200;
	private static final int MAX_ID = 16;
	private static final int MAX_FILE = 80;

	private GuideImageLinks()
	{
	}

	/** The URL unchanged when it passes every check, otherwise null. */
	static String accepted(String url)
	{
		if (url == null || url.length() > MAX_URL || !url.startsWith(PREFIX))
		{
			return null;
		}
		String path = url.substring(PREFIX.length());
		int slash = path.indexOf('/');
		if (slash < 1 || slash > MAX_ID || path.indexOf('/', slash + 1) >= 0)
		{
			return null;
		}
		String file = path.substring(slash + 1);
		if (file.isEmpty() || file.length() > MAX_FILE || file.charAt(0) == '.')
		{
			return null;
		}
		for (int i = 0; i < slash; i++)
		{
			if (!isAlphaNumeric(path.charAt(i)))
			{
				return null;
			}
		}
		// letters, digits, '-', '_' and single dots only: no '%', '?', '#',
		// '@', ':', '\\', whitespace, control or non-ASCII characters, and no
		// ".." - so there is nothing for two URL parsers to disagree about
		for (int i = 0; i < file.length(); i++)
		{
			char c = file.charAt(i);
			if (c == '.')
			{
				if (i + 1 < file.length() && file.charAt(i + 1) == '.')
				{
					return null;
				}
			}
			else if (!isAlphaNumeric(c) && c != '-' && c != '_')
			{
				return null;
			}
		}
		String lower = file.toLowerCase(Locale.ROOT);
		if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg"))
		{
			return null;
		}
		// the browser launcher and the HTTP client both parse the string again;
		// it must mean the same thing to them
		try
		{
			java.net.URI uri = new java.net.URI(url);
			if (!HOST.equals(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null
				|| uri.getQuery() != null || uri.getFragment() != null)
			{
				return null;
			}
		}
		catch (java.net.URISyntaxException e)
		{
			return null;
		}
		return url;
	}

	/**
	 * The screenshot URL of a trimmed {@code {{Extimage|url|...}}} line, or
	 * null when the line is not that template or its URL does not qualify.
	 * Only the first argument is read, so layout arguments and anything typed
	 * after the closing braces never matter.
	 */
	static String fromExtimage(String line)
	{
		if (line == null || !line.regionMatches(true, 0, TEMPLATE, 0, TEMPLATE.length()))
		{
			return null;
		}
		int start = TEMPLATE.length();
		int limit = Math.min(line.length(), start + MAX_URL + 1);
		int end = start;
		while (end < limit && line.charAt(end) != '|' && line.charAt(end) != '}')
		{
			end++;
		}
		if (end == limit && end < line.length())
		{
			return null;
		}
		return accepted(line.substring(start, end).trim());
	}

	private static boolean isAlphaNumeric(char c)
	{
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
	}
}
