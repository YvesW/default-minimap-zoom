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
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.*;


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

	@Inject
	private Client client;

	@Inject
	private DefaultMinimapZoomConfig config;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ClientThread clientThread;

	@Override
	public void startUp() throws Exception {
		updateConfig();
		if (client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
			loggedInOnce = true;
			if (zoomWhenRightClick) {
				clientThread.invokeLater(() -> { //Could replace with method reference but cba for now.
					getProcessedMinimapArea(); //If player is still hopping or on red login screen, it'll run getProcessedMinimapArea() in a bit again anyway.
				});
			}
		}
		mouseManager.registerMouseListener(this);
	}

	@Override
	public void shutDown() {
		mouseManager.unregisterMouseListener(this);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		if (e.getGroup().equals("DefaultMinimapZoom")) {
			updateConfig();
			if (e.getKey().equals("zoomLevel") && client.isMinimapZoom() && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
				client.setMinimapZoom(zoomLevel);
			}
			if (zoomWhenRightClick && e.getKey().equals("zoomWhenRightClick") && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
				clientThread.invokeLater(() -> {
					getProcessedMinimapArea();
				});
			}
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
			clientThread.invokeLater(() -> {
				getProcessedMinimapArea();
			});
		}
	}

	@Subscribe
	public void onCanvasSizeChanged(CanvasSizeChanged canvasSizeChanged) { //Also screws over the area, at least in resizable mode.
		if (zoomWhenRightClick && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN) {
			clientThread.invokeLater(() -> {
				getProcessedMinimapArea();
			});
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		//Account for dragging the minimap. Earlier experimentation with onDraggingWidgetChanged, getDraggedWidget, getDraggedOnWidget, client.isDraggingWidget was unsuccesful.
		//Could potentially also check for client.isResized(), but this will account for any other discrepancies in the bounds as well.
		if (zoomWhenRightClick && client.getGameState() != null && client.getGameState() == GameState.LOGGED_IN && client.isMinimapZoom() && getMinimapWidget() != null) {
			Rectangle currentMinimapBounds = getMinimapWidget().getBounds();
			if (previousMinimapBounds != null && !previousMinimapBounds.equals(currentMinimapBounds)) {
				clientThread.invokeLater(() -> {
					getProcessedMinimapArea();
				});
			}
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
		return mouseEvent;
	}

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

	//These methods are unused but required to be present in a MouseListener implementation
	// ------------- Unused MouseListener methods -------------
	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent) {
		//Can add a hotkey listener and get the drag hoykey usig configmanager, but even then it looks like mousePressed or mouseDragged doesn't work while dragging widgets (at least with isAltDown)...
		//Will get fixed while flicking the plugin on/off or hopping anyway. => Added onGameTick check
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