package com.hcimguide;

import java.util.Locale;

/**
 * Detection of video-guide links (YouTube/Streamable) in guide content, so
 * steps that reference a video can offer a "watch in browser" action.
 *
 * Guide text is REMOTE data, so only an exact host whitelist ever qualifies -
 * no other URLs are surfaced as clickable anywhere in the plugin. Opening is
 * always a deliberate user click routed through RuneLite's LinkBrowser; the
 * plugin itself never fetches these URLs.
 */
final class VideoLinks
{
	private static final String TRAILING_PUNCTUATION = ")].,;:!?'\"";
	/** Longest URL worth considering; also bounds all per-candidate work. */
	private static final int MAX_URL = 2000;

	private VideoLinks()
	{
	}

	/** True only for http(s) URLs whose exact host is a known video site. */
	static boolean isVideoUrl(String url)
	{
		if (url == null)
		{
			return false;
		}
		String u = url.trim();
		if (u.length() > MAX_URL)
		{
			return false;
		}
		// fail closed on anything that couldn't be a clean ASCII URL: spaces,
		// control chars and backslashes break LinkBrowser (or parse
		// differently across URL implementations), and non-ASCII rejects both
		// homoglyph hosts and our own "…" truncation marker
		for (int i = 0; i < u.length(); i++)
		{
			char c = u.charAt(i);
			if (c <= ' ' || c > '~' || c == '\\')
			{
				return false;
			}
		}
		u = u.toLowerCase(Locale.ROOT);
		String rest;
		if (u.startsWith("https://"))
		{
			rest = u.substring(8);
		}
		else if (u.startsWith("http://"))
		{
			rest = u.substring(7);
		}
		else
		{
			return false;
		}
		int end = rest.length();
		for (int i = 0; i < rest.length(); i++)
		{
			char c = rest.charAt(i);
			if (c == '/' || c == '?' || c == '#')
			{
				end = i;
				break;
			}
		}
		String host = rest.substring(0, end);
		// userinfo tricks ("https://youtube.com@evil.example/") never qualify
		if (host.contains("@"))
		{
			return false;
		}
		int colon = host.indexOf(':');
		if (colon >= 0)
		{
			host = host.substring(0, colon);
		}
		if (host.startsWith("www."))
		{
			host = host.substring(4);
		}
		else if (host.startsWith("m."))
		{
			host = host.substring(2);
		}
		// exact equality: "youtube.com.evil.example" must not pass
		return host.equals("youtube.com") || host.equals("youtu.be") || host.equals("streamable.com");
	}

	/**
	 * First whitelisted video URL embedded in plain step text (the wikitext
	 * guide pastes bare URLs), with trailing prose punctuation stripped.
	 * Returns the URL in its ORIGINAL case - video ids are case-sensitive.
	 */
	static String firstVideoUrl(String text)
	{
		if (text == null)
		{
			return null;
		}
		int idx = 0;
		while ((idx = text.indexOf("http", idx)) >= 0)
		{
			int end = idx;
			while (end < text.length() && !Character.isWhitespace(text.charAt(end)))
			{
				end++;
			}
			// strip trailing prose punctuation BY INDEX - one substring total,
			// so hostile ")))...)"-tails stay linear, not quadratic
			int trimEnd = end;
			while (trimEnd > idx && TRAILING_PUNCTUATION.indexOf(text.charAt(trimEnd - 1)) >= 0)
			{
				trimEnd--;
			}
			if (trimEnd - idx <= MAX_URL)
			{
				String candidate = text.substring(idx, trimEnd);
				if (isVideoUrl(candidate))
				{
					return candidate;
				}
			}
			idx = end + 1;
		}
		return null;
	}
}
