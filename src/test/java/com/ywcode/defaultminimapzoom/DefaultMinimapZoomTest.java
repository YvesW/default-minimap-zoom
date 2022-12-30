package com.ywcode.defaultminimapzoom;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DefaultMinimapZoomTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DefaultMinimapZoomPlugin.class);
		RuneLite.main(args);
	}
}