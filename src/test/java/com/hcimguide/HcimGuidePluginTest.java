package com.hcimguide;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches a development RuneLite client with this plugin loaded.
 * Run this class from your IDE (add -ea to VM options).
 *
 * NOT a test suite - it deliberately contains no @Test methods. The name
 * and location follow the RuneLite plugin template convention, which every
 * Hub plugin ships; coverage tooling should not count it.
 */
public class HcimGuidePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(HcimGuidePlugin.class);
		RuneLite.main(args);
	}
}
