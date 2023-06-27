# Default Minimap Zoom
Also annoyed by having to zoom out or in every time you restart the client?<br>

**Default Minimap Zoom allows the user to set the default minimap zoom**, so they don't always have to zoom in or out when starting RuneLite.<br>
The currently active zoom level will also be changed while changing the config value, so you can see what you are setting your default minimap zoom to.

Via config, the default zoom value can be applied...<br>
- when starting the client,
- on every login,
- when hopping worlds,
- or when right-clicking the minimap.

Please make sure "Zoom" is enabled in the Minimap plugin and make sure that the Minimap plugin is enabled as well if you wish this plugin to set the default zoom level!

Example config of being maximally zoomed out when starting the client and zooming out when right-clicking the minimap:<br>
![Example config](src/main/resources/img/example-config.PNG)<br>

If you experience any issues or have any concerns, please reach out to YvesW via a GitHub Issue, by Discord direct messaging or via the RuneLite Discord by mentioning @Yves W.

## Changelog

02-2023:
- Fixed a bug that incorrectly recalculated the minimap area when the side-panel got opened/closed in resizable mode. "Set zoom when right-clicking minimap" should work again when in resizable.

01-2023:
- Added recalculation of the minimap area in case the minimap gets moved by the user. Hopping or flicking the option off and on is no longer needed.