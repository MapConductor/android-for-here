# HerePolygonController

## Signature

```kotlin
class HerePolygonController(
    polygonManager: PolygonManagerInterface<HereActualPolygon> = PolygonManager(),
    renderer: HerePolygonOverlayRenderer,
) : PolygonController<HereActualPolygon>(polygonManager, renderer)
```

## Description

The `HerePolygonController` is the primary class for managing and displaying polygon overlays on a
HERE map. It serves as a specialized implementation of the generic `PolygonController`, tailored
specifically for the HERE Maps SDK environment.

This controller orchestrates the entire lifecycle of polygons, from data management to visual
rendering. It connects the core `PolygonManager`, which handles the state of `HereActualPolygon`
objects, with the `HerePolygonOverlayRenderer`, which is responsible for drawing the polygons onto
the map canvas.

Use this controller to add, remove, update, and interact with polygons on your map.

## Parameters

This section describes the parameters for the `HerePolygonController` constructor.

- `polygonManager`
    - Type: `PolygonManagerInterface<HereActualPolygon>`
    - Default: `PolygonManager()`
    - Description: The manager responsible for handling the state and lifecycle of polygon data.
- `renderer`
    - Type: `HerePolygonOverlayRenderer`
    - Default: `*none*`
    - Description: The HERE-specific renderer that draws the polygons on the map view. This
                   parameter is required.
## Example

The following example demonstrates how to initialize the `HerePolygonController`. You typically
create it once per map instance.

```kotlin
// HerePolygonController is typically created internally by HereMapView.
// Polygons are added via PolygonState in the Compose content lambda:

HereMapView(state = mapState) {
    Polygon(
        state = rememberPolygonState(
            id = "area-1",
            points = listOf(
                GeoPoint(35.6812, 139.7671),
                GeoPoint(35.6895, 139.6917),
                GeoPoint(35.7100, 139.8107)
            ),
            fillColor = Color.Blue.copy(alpha = 0.3f),
            strokeColor = Color.Blue
        )
    )
}
```