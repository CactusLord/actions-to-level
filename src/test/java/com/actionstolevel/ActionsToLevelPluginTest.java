package com.actionstolevel;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches a RuneLite developer client with this plugin loaded: ./gradlew run
 */
public class ActionsToLevelPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ActionsToLevelPlugin.class);
		RuneLite.main(args);
	}
}
