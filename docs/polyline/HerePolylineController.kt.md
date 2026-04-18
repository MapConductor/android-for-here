# HerePolylineController

The `HerePolylineController` is a specialized controller responsible for managing and rendering
polyline overlays on a HERE map. It extends the generic `PolylineController`, tailoring its
functionality specifically for the HERE Maps SDK environment.

This class orchestrates the management of polyline data through a `PolylineManager` and handles the
visual representation on the map using a `HerePolylineOverlayRenderer`. Developers should use this
controller as the primary interface for all polyline-related operations, such as adding, removing,
and updating polylines on the map.

## Signature

```kotlin
class HerePolylineController(
    polylineManager: PolylineManagerInterface<HereActualPolyline> = PolylineManager(),
    renderer: HerePolylineOverlayRenderer,
) : PolylineController<HereActualPolyline>(polylineManager, renderer)
```

## Parameters

This section describes the parameters for the `HerePolylineController` constructor.

- `polylineManager`
    - Type: `PolylineManagerInterface<HereActualPolyline>`
    - Description: (Optional) The manager responsible for the lifecycle and state of polyline
                   objects. If not provided, a default `PolylineManager()` instance is created.
- `renderer`
    - Type: `HerePolylineOverlayRenderer`
    - Description: (Required) The renderer responsible for drawing the polylines onto the HERE map
                   view. This object handles the platform-specific rendering logic.
## Example

The following example demonstrates how to initialize and use the `HerePolylineController` to add a
polyline to a HERE map.

```kotlin
// HerePolylineController is typically created internally by HereMapView.
// Polylines are added via PolylineState in the Compose content lambda:

HereMapView(state = mapState) {
    Polyline(
        state = rememberPolylineState(
            id = "route-66",
            points = listOf(
                GeoPoint(34.0522, -118.2437), // Los Angeles
                GeoPoint(39.7392, -104.9903), // Denver
                GeoPoint(41.8781, -87.6298)   // Chicago
            ),
            strokeColor = Color.Blue,
            strokeWidth = 8.dp
        )
    )
}
```
