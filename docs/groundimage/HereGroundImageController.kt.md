# HereGroundImageController

## Signature

```kotlin
class HereGroundImageController(
    groundImageManager: GroundImageManagerInterface<HereActualGroundImage> = GroundImageManager(),
    renderer: HereGroundImageOverlayRenderer,
) : GroundImageController<HereActualGroundImage>(groundImageManager, renderer)
```

## Description

The `HereGroundImageController` is a specialized controller for managing and rendering ground image
overlays on a HERE map. It serves as a crucial link between the abstract ground image management
logic in `GroundImageController` and the concrete rendering implementation required by the HERE SDK
for Android.

This class takes a `HereGroundImageOverlayRenderer` to handle the actual drawing of images on the
map canvas. It orchestrates the entire lifecycle of ground images, including their creation,
addition, removal, and updates, ensuring they are correctly displayed on the HERE map.

## Parameters

This section describes the parameters for the `HereGroundImageController` constructor.

- `groundImageManager`
    - Type: `GroundImageManagerInterface<HereActualGroundImage>`
    - Default: `GroundImageManager()`
    - Description: The manager responsible for handling the state, data, and lifecycle of all ground
                   images.
- `renderer`
    - Type: `HereGroundImageOverlayRenderer`
    - Default: `-`
    - Description: The platform-specific renderer that draws the ground image overlays onto the
                   associated HERE `MapView`.
## Example

The following example demonstrates how to add a ground image to a HERE map. `HereGroundImageController`
is created internally by `HereMapView`; ground images are added via `GroundImageState` in the
Compose content lambda.

```kotlin
HereMapView(state = mapState) {
    GroundImage(
        state = rememberGroundImageState(
            id = "unique-image-id-1",
            image = BitmapImageProvider(imageBitmap),
            bounds = GeoRectBounds(
                southwest = GeoPoint(52.5200, 13.4000),
                northeast = GeoPoint(52.5300, 13.4100)
            )
        )
    )
}
```