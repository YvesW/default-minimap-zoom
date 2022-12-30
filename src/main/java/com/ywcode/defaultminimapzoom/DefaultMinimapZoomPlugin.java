package com.ywcode.defaultminimapzoom;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.*;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
		name = "Default Minimap Zoom",
		description = "Allows the user to set the default minimap zoom.",
		tags = {"minimap,mini,map,zoom,default"}
)
public class DefaultMinimapZoomPlugin extends Plugin {

	private double zoomLevel;
	private boolean zoomWhenStartingClient;
	private boolean zoomWhenLogin;
	private boolean zoomWhenHopping;
	private boolean currentlyHopping = false;
	private boolean loggedInOnce = false;

	@Inject
	private Client client;

	@Inject
	private DefaultMinimapZoomConfig config;

	@Override
	protected void startUp() throws Exception {
		updateConfig();
		if (client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
			loggedInOnce = true;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		if (e.getGroup().equals("DefaultMinimapZoom")) {
			updateConfig();
			if (e.getKey().equals("zoomLevel") && client.isMinimapZoom() && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
				client.setMinimapZoom(zoomLevel);
			}
		}
	}

	private void updateConfig() {
		zoomLevel = config.zoomLevel().toZoomLevel();
		zoomWhenStartingClient = config.zoomWhenStartingClient();
		zoomWhenLogin = config.zoomWhenLogin();
		zoomWhenHopping = config.zoomWhenHopping();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		//Logged in fires when logged in and after hopping. Hopping fires before logged in when hopping, i.e.
		//fresh login: logged in fires, hopping does not
		//hop: hopping fires, later on logged in fires
		//Changing zoom level when GameState = HOPPING doesn't work, needs to be LOGGED_IN
		if (gameStateChanged.getGameState() == GameState.HOPPING) {
			currentlyHopping = true;
		}
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {
			if ((zoomWhenHopping && currentlyHopping) ||
					(zoomWhenLogin && !currentlyHopping) ||
					(zoomWhenStartingClient && !loggedInOnce)) {
				client.setMinimapZoom(zoomLevel);
			}
			loggedInOnce = true;
			currentlyHopping = false;
		}
	}

	@Provides
	DefaultMinimapZoomConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DefaultMinimapZoomConfig.class);
	}
}
