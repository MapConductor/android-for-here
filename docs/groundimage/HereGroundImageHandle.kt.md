# HereGroundImageHandle

A data class that acts as a handle for a ground image overlay on the map. It encapsulates all the
necessary components and identifiers associated with a single ground image instance.

This handle is typically returned when a ground image is added to the map and can be used to manage
the lifecycle of the image, such as hiding, showing, or removing it.

## Signature

```kotlin
data class HereGroundImageHandle(
    val routeId: String,
    val generation: Long,
    val cacheKey: String,
    val sourceName: String,
    val layerName: String,
    val dataSource: RasterDataSource,
    val layer: MapLayer,
    val tileProvider: GroundImageTileProvider,
)
```

## Parameters

This data class contains the following properties:

- `routeId`
    - Type: `String`
    - Description: The unique identifier for the route associated with this ground image.
- `generation`
    - Type: `Long`
    - Description: A version or generation number for the ground image, used to differentiate
                   between updates.
- `cacheKey`
    - Type: `String`
    - Description: A unique key used for caching the ground image tiles.
- `sourceName`
    - Type: `String`
    - Description: The internal name assigned to the `RasterDataSource`.
- `layerName`
    - Type: `String`
    - Description: The internal name assigned to the `MapLayer`.
- `dataSource`
    - Type: `RasterDataSource`
    - Description: The HERE SDK `RasterDataSource` instance that provides the image data to the map
                   view.
- `layer`
    - Type: `MapLayer`
    - Description: The HERE SDK `MapLayer` instance that renders the ground image on the map.
- `tileProvider`
    - Type: `GroundImageTileProvider`
    - Description: The custom tile provider responsible for supplying image tile data to the
                   `dataSource`.
## Example

While you do not instantiate this class directly, you would receive it from a function that adds a
ground image to the map. You can then use the properties of the handle to interact with the map
layer.

```kotlin
// Assume 'groundImageManager.addGroundImage(...)' returns a HereGroundImageHandle
val groundImageHandle: HereGroundImageHandle? = groundImageManager.addGroundImage(someParameters)

// You can then use the handle to manage the layer
groundImageHandle?.let { handle ->
    // Hide the ground image layer from the map
    handle.layer.isEnabled = false

    // Show the ground image layer again
    handle.layer.isEnabled = true

    // To remove the layer, you would likely pass the handle to another function
    // groundImageManager.removeGroundImage(handle)
}
```