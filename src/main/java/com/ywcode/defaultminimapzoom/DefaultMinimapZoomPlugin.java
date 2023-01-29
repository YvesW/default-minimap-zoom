package com.ywcode.defaultminimapzoom;

import com.google.inject.Provides;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.*;
import net.runelite.client.input.*;
import net.runelite.client.plugins.*;
import net.runelite.client.util.*;


@Slf4j
@PluginDescriptor(
		name = "Default Minimap Zoom",
		description = "Allows the user to set the default minimap zoom.",
		tags = {"minimap,mini,map,zoom,default"}
)
public class DefaultMinimapZoomPlugin extends Plugin implements MouseListener {

	private double zoomLevel;
	private boolean zoomWhenStartingClient;
	private boolean zoomWhenLogin;
	private boolean zoomWhenHopping;
	private boolean zoomWhenRightClick;
	private boolean currentlyHopping = false;
	private boolean loggedInOnce = false;
	private Area preprocessedMinimapArea;
	private Area processedMinimapArea;
	private static Rectangle previousMinimapBounds;
	private static Keybind dragHotkey;
	private boolean inOverlayManagingMode = false;

	@Inject
	private Client client;

	@Inject
	private DefaultMinimapZoomConfig config;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientThread clientThread;

	@Override
	public void startUp() throws Exception {
		updateConfig();
		dragHotkey = configManager.getConfiguration("runelite", "dragHotkey", Keybind.class);
		if (client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
			loggedInOnce = true;
			if (zoomWhenRightClick) {
				getProcessedMinimapArea(); //If player is still hopping or on red login screen, it'll run getProcessedMinimapArea() in a bit again anyway.
			}
		}
		mouseManager.registerMouseListener(this);
		keyManager.registerKeyListener(hotkeyListener);
	}

	@Override
	public void shutDown() {
		mouseManager.unregisterMouseListener(this);
		keyManager.unregisterKeyListener(hotkeyListener);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged) {
		if (configChanged.getGroup().equals("DefaultMinimapZoom")) {
			updateConfig();
			if (configChanged.getKey().equals("zoomLevel") && client.isMinimapZoom() && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
				client.setMinimapZoom(zoomLevel);
			}
			if (zoomWhenRightClick && configChanged.getKey().equals("zoomWhenRightClick") && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
				getProcessedMinimapArea();
			}
		}
		if (configChanged.getGroup().equals("runelite") && configChanged.getKey().equals("dragHotkey")) {
			dragHotkey = configManager.getConfiguration("runelite", "dragHotkey", Keybind.class);
		}
	}

	private void updateConfig() {
		zoomLevel = config.zoomLevel().toZoomLevel();
		zoomWhenStartingClient = config.zoomWhenStartingClient();
		zoomWhenLogin = config.zoomWhenLogin();
		zoomWhenHopping = config.zoomWhenHopping();
		zoomWhenRightClick = config.zoomWhenRightClick();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		//Logged in fires when logged in and after hopping. Hopping fires before logged in when hopping, i.e.
		//fresh login: logged in fires, hopping does not
		//hop: hopping fires, later on logged in fires
		//Changing zoom level when GameState == HOPPING doesn't work, needs to be LOGGED_IN
		if (gameStateChanged.getGameState() == GameState.HOPPING) {
			currentlyHopping = true;
		}
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {
			if (client.isMinimapZoom() &&
					((zoomWhenHopping && currentlyHopping) ||
					(zoomWhenLogin && !currentlyHopping) ||
					(zoomWhenStartingClient && !loggedInOnce))) {
				client.setMinimapZoom(zoomLevel);
			}
			loggedInOnce = true;
			currentlyHopping = false;
		}
	}

	@Subscribe
	public void onWidgetLoaded (WidgetLoaded widgetLoaded) { //Widget has not loaded yet while GameState == LOGGED IN, so get area when widget has loaded.
		if (zoomWhenRightClick && widgetLoaded.getGroupId() == WidgetID.MINIMAP_GROUP_ID) { //Works for both fixed and the two resizable modes
			clientThread.invokeLater(() ->	{
				getProcessedMinimapArea();
			});
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed widgetClosed) { //Widget area is incorrect on Login Click to Play Screen, so get area when that widget is closed.
		if (zoomWhenRightClick && widgetClosed.getGroupId() == WidgetID.LOGIN_CLICK_TO_PLAY_GROUP_ID) {
			getProcessedMinimapArea();
		}
	}

	@Subscribe
	public void onCanvasSizeChanged(CanvasSizeChanged canvasSizeChanged) { //Also screws over the area, at least in resizable mode.
		if (zoomWhenRightClick && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
			checkIfMinimapChanged();
		}
	}

	@Subscribe
	public void onFocusChanged(FocusChanged focusChanged) {
		if (zoomWhenRightClick && inOverlayManagingMode && !focusChanged.isFocused() && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN && client.isMinimapZoom() && client.isResized()) {
			inOverlayManagingMode = false;
			checkIfMinimapChanged();
		}
	}

	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent) {
		if (zoomWhenRightClick && client.isMinimapZoom() && mouseEvent.getButton() == 3 && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
			if (processedMinimapArea != null && processedMinimapArea.contains(mouseEvent.getPoint())) { //If right-clicked on minimap
				client.setMinimapZoom(zoomLevel);
				mouseEvent.consume(); //If this is racey with the internal right-click on minimap code, or if this might trigger Jagex's anti-cheat, please let me know. However, a simple consume should definitely not trigger anti-cheat.
			}
		}
		//Doesn't seem to trigger while dragHotkey is being pressed (while inOverlayManagingMode), since the mouseEvent is probably consumed by OverlayRenderer.java
		//For that reason, let's use workaround by calling checkIfMinimapChanged() on hotkeyReleased() and onFocusChanged() if hotkey is currently down (might not catch hotkeyReleased event due to focus change)
		return mouseEvent;
	}

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> dragHotkey) {
		@Override
		public void hotkeyPressed() {
			if (zoomWhenRightClick && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN && client.isMinimapZoom() && client.isResized()) {
				inOverlayManagingMode = true;
			}
		}

		@Override
		public void hotkeyReleased() {
			//Account for dragging the minimap. Earlier experimentation with onDraggingWidgetChanged, getDraggedWidget, getDraggedOnWidget, client.isDraggingWidget was unsuccesful.
			if (zoomWhenRightClick && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN && client.isMinimapZoom() && client.isResized()) {
				inOverlayManagingMode = false;
				checkIfMinimapChanged();
			}
		}
	};

	private Widget getMinimapWidget() {
		if (client.isResized()) {
			if (client.getVarbitValue(Varbits.SIDE_PANELS) == 1) {
				return client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_DRAW_AREA);
			}
			return client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_STONES_DRAW_AREA);
		}
		return client.getWidget(WidgetInfo.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
	}

	private void getProcessedMinimapArea() {
		clientThread.invokeLater(() -> {
			Widget MinimapWidget = getMinimapWidget();
			if (MinimapWidget == null || MinimapWidget.isHidden()) {
				processedMinimapArea = null;
			} else {
				Rectangle minimapBounds = MinimapWidget.getBounds();
				previousMinimapBounds = minimapBounds;
				if (!client.isResized()) {
					//It looks like RL's rightclick area for resetting the zoom is bigger than the Ellipse in fixed mode, so Rectangle2d it is.
					preprocessedMinimapArea = new Area(new Rectangle2D.Double(minimapBounds.getX(), minimapBounds.getY(), minimapBounds.getWidth(), minimapBounds.getHeight()));
					//FIXED MODE: Run energy orb, special attack orb, wiki orb, and compass overlap with the preprocessedMinimapArea in fixed mode.
					//Hp orb, prayer orb, map orb and bonds orb don't overlap in fixed mode.
					Widget energyOrbMinimapWidget = client.getWidget(WidgetInfo.MINIMAP_RUN_ORB.getGroupId(), 29); //Energy orb
					removeOrbArea(energyOrbMinimapWidget);
					Widget specOrbMinimapWidget = client.getWidget(WidgetInfo.MINIMAP_SPEC_ORB.getGroupId(), 37); //Spec orb
					removeOrbArea(specOrbMinimapWidget);
					//RuneLite's rightclick on minimap seems to cut into the click area from the wiki button a bit.
					//This means that a small part of the wiki button will reset the zoom to the wrong level, but so be it.
					Widget wikiOrbMinimapWidget = client.getWidget(WidgetInfo.MINIMAP_WIKI_BANNER_PARENT).getChild(0); //Wiki orb
					removeOrbArea(wikiOrbMinimapWidget);
					Widget compassMinimapWidget = client.getWidget(WidgetInfo.FIXED_VIEWPORT_MINIMAP.getGroupId(), 23); //Compass
					removeOrbArea(compassMinimapWidget);
				} else {
					//For the resizable modes however, it looks to be closer to Ellipse2D!
					//There are a couple pixels on the edge of the Ellipse that RL does include but the Ellipse doesn't, but it's a tiny difference and very difficult to click on. Should be acceptable.
					preprocessedMinimapArea = new Area(new Ellipse2D.Double(minimapBounds.getX(), minimapBounds.getY(), minimapBounds.getWidth(), minimapBounds.getHeight()));
					//RESIZABLE CLASSIC (STONES DRAW AREA): since we use Ellipse, only the world map orb overlaps with the preprocessedMinimapArea
					//Hp orb, prayer orb, run energy orb, special attack orb, wiki orb, bond orb, activity tracker orb and compass don't overlap in resizable classic.
					//RESIZABLE MODERN (**NO** STONES DRAW AREA): since we use Ellipse, only the world map orb overlaps with the preprocessedMinimapArea
					//Hp orb, prayer orb, run energy orb, special attack orb, wiki orb, bond orb, activity tracker orb and compass don't overlap in resizable modern.
					Widget worldmapOrbMinimapWidget = client.getWidget(WidgetInfo.MINIMAP_WORLDMAP_OPTIONS); //World map orb
					removeOrbArea(worldmapOrbMinimapWidget);
				}
				processedMinimapArea = preprocessedMinimapArea;
			}
		});
	}

	private void removeOrbArea(Widget minimapWidget) {
		Area OrbMinimapArea;
		if (minimapWidget != null && !minimapWidget.isHidden()) {
			Rectangle minimapWidgetBounds = minimapWidget.getBounds();
			//The clickbox of all orbs seems to be a rectangular (not an ellipse).
			OrbMinimapArea = new Area(new Rectangle2D.Double(minimapWidgetBounds.getX(), minimapWidgetBounds.getY(), minimapWidgetBounds.getWidth(), minimapWidgetBounds.getHeight()));
			preprocessedMinimapArea.subtract(OrbMinimapArea);
		}
	}

	private void checkIfMinimapChanged() {
		clientThread.invokeLater(() -> {
			if (getMinimapWidget() != null) {
				Rectangle currentMinimapBounds = getMinimapWidget().getBounds();
				if (previousMinimapBounds != null && !previousMinimapBounds.equals(currentMinimapBounds)) {
					getProcessedMinimapArea();
				}
			}
		});
	}

	//These methods are unused but required to be present in a MouseListener implementation
	// ------------- Unused MouseListener methods -------------
	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent) {
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent) {
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent mouseEvent) {
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent mouseEvent) {
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent mouseEvent) {
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent) {
		return mouseEvent;
	}
	// ------------- End unused MouseListener methods -------------

	@Provides
	DefaultMinimapZoomConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DefaultMinimapZoomConfig.class);
	}
}