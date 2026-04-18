# HereCircleController

### Signature

```kotlin
class HereCircleController(
    circleManager: CircleManager<HereActualCircle> = CircleManager(),
    renderer: HereCircleOverlayRenderer,
) : CircleController<HereActualCircle>(circleManager, renderer)
```

### Description

The `HereCircleController` is a specialized controller responsible for managing and rendering circle
overlays on a HERE map. It serves as a concrete implementation of the abstract `CircleController`,
bridging the generic circle management logic with the platform-specific rendering capabilities of
the HERE Maps SDK.

This controller orchestrates the `CircleManager`, which handles the data and state of circle
objects, and the `HereCircleOverlayRenderer`, which performs the actual drawing on the map canvas.
By inheriting from `CircleController<HereActualCircle>`, it exposes a consistent API for adding,
updating, and removing circles, while handling the underlying HERE-specific implementation details.

### Parameters

- `circleManager`
    - Type: `CircleManager<HereActualCircle>`
    - Description: The manager responsible for handling the collection and state of all circle data
                   models. It defaults to a new `CircleManager()` instance if not provided.
- `renderer`
    - Type: `HereCircleOverlayRenderer`
    - Description: The renderer that draws the `HereActualCircle` objects onto the HERE map view.
                   This parameter is required.
### Example

The following example demonstrates how to add a circle to a HERE map. `HereCircleController` is
created internally by `HereMapView`; circles are added via `CircleState` in the Compose content
lambda.

```kotlin
HereMapView(state = mapState) {
    Circle(
        state = rememberCircleState(
            id = "berlin-circle-1",
            center = GeoPoint(52.5200, 13.4050),
            radius = 1000.0,
            fillColor = Color.Blue.copy(alpha = 0.3f),
            strokeColor = Color.Blue
        )
    )
}
```