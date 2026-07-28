package com.hcimguide;

import java.awt.Font;
import java.awt.Graphics2D;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.runelite.client.ui.FontManager;

/** Resolves bounded, configurable fonts for every text-bearing plugin overlay. */
final class OverlayFonts
{
	private static final int MAX_CACHED_FONTS = 128;
	private static final int MAX_CUSTOM_FAMILY_LENGTH = 80;

	/**
	 * Access-order LRU: users can explore installed fonts and sizes without
	 * retaining every combination for the lifetime of the RuneLite client.
	 */
	private static final Map<String, Font> CACHE = new LinkedHashMap<String, Font>(32, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Font> eldest)
		{
			return size() > MAX_CACHED_FONTS;
		}
	};

	private OverlayFonts()
	{
	}

	static void apply(Graphics2D graphics, HcimGuideConfig config)
	{
		graphics.setFont(resolve(graphics.getFont(), config.overlayFontFamily(),
			config.customOverlayFontFamily(), config.overlayFontWeight(), config.overlayFontSize()));
	}

	static Font resolve(Font fallback, HcimGuideConfig.OverlayFontFamily family,
		String customFamily, HcimGuideConfig.OverlayFontWeight weight, int requestedSize)
	{
		HcimGuideConfig.OverlayFontFamily safeFamily = family == null
			? HcimGuideConfig.OverlayFontFamily.SANS_SERIF : family;
		HcimGuideConfig.OverlayFontWeight safeWeight = weight == null
			? HcimGuideConfig.OverlayFontWeight.FAMILY_DEFAULT : weight;
		int size = Math.max(8, Math.min(40, requestedSize));
		String custom = sanitizeCustomFamily(customFamily);
		String fallbackKey = fallback == null ? "null"
			: fallback.getName() + ':' + fallback.getStyle();
		String key = safeFamily.name() + ':' + custom.toLowerCase(Locale.ROOT)
			+ ':' + safeWeight.name() + ':' + size + ':' + fallbackKey;
		synchronized (CACHE)
		{
			Font cached = CACHE.get(key);
			if (cached != null)
			{
				return cached;
			}
			Font created = create(fallback, safeFamily, custom, safeWeight, size);
			CACHE.put(key, created);
			return created;
		}
	}

	private static Font create(Font fallback, HcimGuideConfig.OverlayFontFamily family,
		String customFamily, HcimGuideConfig.OverlayFontWeight weight, int size)
	{
		Font base;
		if (family == HcimGuideConfig.OverlayFontFamily.CLIENT_DEFAULT)
		{
			base = fallback != null ? fallback : FontManager.getRunescapeFont();
		}
		else if (family.isRuneScape())
		{
			switch (family)
			{
				case SMALL:
					base = FontManager.getRunescapeSmallFont();
					break;
				case BOLD:
					base = FontManager.getRunescapeBoldFont();
					break;
				case RUNESCAPE:
				case REGULAR:
				default:
					base = FontManager.getRunescapeFont();
					break;
			}
		}
		else
		{
			int physicalStyle = weight == HcimGuideConfig.OverlayFontWeight.FAMILY_DEFAULT
				? family.getDefaultStyle() : weight.resolveStyle(family);
			String requestedName = family == HcimGuideConfig.OverlayFontFamily.CUSTOM
				? customFamily : family.getAwtName();
			if (requestedName == null || requestedName.isEmpty())
			{
				requestedName = Font.SANS_SERIF;
			}
			base = installedOrFallback(requestedName, physicalStyle, size);
		}
		int style = weight == HcimGuideConfig.OverlayFontWeight.FAMILY_DEFAULT
			? base.getStyle() : weight.resolveStyle(family);
		return base.deriveFont(style, (float) size);
	}

	/** Java silently maps unknown physical names to Dialog; make the fallback explicit. */
	private static Font installedOrFallback(String requestedName, int style, int size)
	{
		Font candidate = new Font(requestedName, style, size);
		String resolved = candidate.getFamily(Locale.ROOT);
		if (!Font.DIALOG.equalsIgnoreCase(requestedName)
			&& Font.DIALOG.equalsIgnoreCase(resolved)
			&& !requestedName.equalsIgnoreCase(resolved))
		{
			return new Font(Font.SANS_SERIF, style, size);
		}
		return candidate;
	}

	private static String sanitizeCustomFamily(String family)
	{
		if (family == null)
		{
			return "";
		}
		String trimmed = family.trim();
		return trimmed.length() <= MAX_CUSTOM_FAMILY_LENGTH
			? trimmed : trimmed.substring(0, MAX_CUSTOM_FAMILY_LENGTH);
	}

	/** Drop cached native font objects when the plugin is disabled. */
	static void clear()
	{
		synchronized (CACHE)
		{
			CACHE.clear();
		}
	}

	static int cacheSizeForTesting()
	{
		synchronized (CACHE)
		{
			return CACHE.size();
		}
	}
}
