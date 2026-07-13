package com.hcimguide;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches a development RuneLite client with this plugin loaded.
 * Run this class from your IDE (add -ea to VM options).
 */
public class HcimGuidePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(HcimGuidePlugin.class);
		RuneLite.main(args);
	}
}
