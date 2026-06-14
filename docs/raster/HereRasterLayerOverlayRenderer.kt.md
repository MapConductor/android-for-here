# HereRasterLayerOverlayRenderer

The `HereRasterLayerOverlayRenderer` class is responsible for rendering and managing raster tile
layers on a HERE map. It implements the `RasterLayerOverlayRendererInterface` to handle the
lifecycle of raster layers, including their creation, modification, and removal, by translating
abstract `RasterLayerState` objects into concrete HERE SDK `MapLayer` instances.

This renderer supports various raster sources, such as URL templates (XYZ and TMS) and ArcGIS map
services. It manages the underlying `RasterDataSource` and `MapLayer` objects from the HERE SDK.

```kotlin
class HereRasterLayerOverlayRenderer(
    private val holder: HereViewHolder,
    private val tileServer: LocalTileServer,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : RasterLayerOverlayRendererInterface<HereRasterLayerHandle>
```

## Constructor

### Signature

```kotlin
HereRasterLayerOverlayRenderer(
    holder: HereViewHolder,
    tileServer: LocalTileServer,
    coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default)
)
```

### Description

Creates a new instance of the `HereRasterLayerOverlayRenderer`.

### Parameters

- `holder`
    - Type: `HereViewHolder`
    - Description: The view holder that provides access to the `MapView` and `HereMap` instances.
- `tileServer`
    - Type: `LocalTileServer`
    - Description: Local tile server used when raster tiles need to be proxied, such as when
                   applying opacity for HERE SDK raster layers.
- `coroutine`
    - Type: `CoroutineScope`
    - Description: The coroutine scope used for executing asynchronous operations. Defaults to
                   `CoroutineScope(Dispatchers.Default)`.
## Methods

### onAdd

#### Signature

```kotlin
override suspend fun onAdd(
    data: List<RasterLayerOverlayRendererInterface.AddParamsInterface>,
): List<HereRasterLayerHandle?>
```

#### Description

Adds one or more new raster layers to the map based on the provided state data. For each item in the
input list, it creates a new `RasterDataSource` and a corresponding `MapLayer`.

#### Parameters

- `data`
    - Type: `List<RasterLayerOverlayRendererInterface.AddParamsInterface>`
    - Description: A list of parameters, where each element contains the `RasterLayerState` for a
                   new layer to be added.
#### Returns

A `List` of `HereRasterLayerHandle?` objects. Each handle corresponds to a newly created layer. An
element will be `null` if the layer creation failed (e.g., due to an unsupported source type or
invalid configuration).

### onChange

#### Signature

```kotlin
override suspend fun onChange(
    data: List<RasterLayerOverlayRendererInterface.ChangeParamsInterface<HereRasterLayerHandle>>,
): List<HereRasterLayerHandle?>
```

#### Description

Processes updates for existing raster layers. If the underlying `source` or `opacity` of a layer has
changed, the old layer is destroyed and a new one is created. If only other properties like
visibility have changed, the existing `MapLayer` is updated in place.

#### Parameters

- `data`
    - Type: `List<RasterLayerOverlayRendererInterface.ChangeParamsInterface<HereRasterLayerHandle>>`
    - Description: A list of change parameters. Each element contains the previous layer entity
                   (`prev`) and the new state information (`current`).
#### Returns

A `List` of `HereRasterLayerHandle?` objects representing the state of the layers after the update.
This could be the original handle, a new handle if the layer was recreated, or `null` if recreation
failed.

### onRemove

#### Signature

```kotlin
override suspend fun onRemove(data: List<RasterLayerEntityInterface<HereRasterLayerHandle>>)
```

#### Description

Removes one or more raster layers from the map. This method destroys the associated `MapLayer` and
`RasterDataSource` for each layer, freeing up their resources.

#### Parameters

- `data`
    - Type: `List<RasterLayerEntityInterface<HereRasterLayerHandle>>`
    - Description: A list of layer entities to be removed from the map.
#### Returns

This method does not return a value.

### onPostProcess

#### Signature

```kotlin
override suspend fun onPostProcess()
```

#### Description

A lifecycle method called after all add, change, and remove operations for a given update cycle are
complete. In this implementation, the method is empty and performs no action.

## Data Classes

### HereRasterLayerHandle

A data class that acts as a handle to the native HERE SDK objects associated with a rendered raster
layer. It encapsulates the `RasterDataSource` and `MapLayer` for easy management.

#### Signature

```kotlin
data class HereRasterLayerHandle(
    val dataSource: RasterDataSource,
    val layer: MapLayer,
    val sourceName: String,
    val layerName: String,
    val routeId: String?,
)
```

#### Properties

- `dataSource`
    - Type: `RasterDataSource`
    - Description: The HERE SDK data source providing the raster tiles.
- `layer`
    - Type: `MapLayer`
    - Description: The HERE SDK map layer that displays the raster data.
- `sourceName`
    - Type: `String`
    - Description: The unique name assigned to the data source.
- `layerName`
    - Type: `String`
    - Description: The unique name assigned to the map layer.
- `routeId`
    - Type: `String?`
    - Description: Local tile server route used by the opacity proxy, or `null` when the native
                   raster source can be used directly.

## Opacity proxy

HERE SDK raster layers are backed by `RasterDataSource` URL providers. When `RasterLayerState`
opacity is below `1.0f`, the renderer registers a local tile proxy route and points HERE at that
local URL. The proxy fetches the original tile, applies the requested alpha to the bitmap, and
returns a PNG tile to the SDK.

Fetched source tiles are stored in an in-memory LRU cache keyed by URL before opacity is applied.
The default cache size is 16 MiB. This avoids repeated network fetches when HERE requests the same
tile again for the current proxied layer.
## Example

The following example demonstrates how to instantiate the `HereRasterLayerOverlayRenderer` and use
it to add a new raster layer from a URL template.

```kotlin
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.here.HereViewHolder
import com.mapconductor.here.raster.HereRasterLayerOverlayRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Assume 'hereViewHolder' is an initialized instance of HereViewHolder
// containing a valid MapView and HereMap.
val hereViewHolder: HereViewHolder = /* ... */

// 1. Instantiate the renderer
val rasterRenderer = HereRasterLayerOverlayRenderer(hereViewHolder, localTileServer)

// 2. Define the state for the new raster layer
val openStreetMapLayerState = RasterLayerState(
    id = "osm-layer-1",
    visible = true,
    source = RasterLayerSource.UrlTemplate(
        template = "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
        scheme = TileScheme.XYZ,
        minZoom = 0,
        maxZoom = 19
    )
)

// 3. Create the parameters for the 'onAdd' method
// In a real application, this would likely be part of a larger system.
// For this example, we create a simple mock implementation.
data class AddParams(override val state: RasterLayerState) : RasterLayerOverlayRendererInterface.AddParamsInterface

val addParams = listOf(AddParams(openStreetMapLayerState))

// 4. Call 'onAdd' within a coroutine to add the layer to the map
CoroutineScope(Dispatchers.Main).launch {
    val layerHandles = rasterRenderer.onAdd(addParams)

    if (layerHandles.isNotEmpty() && layerHandles[0] != null) {
        println("Successfully added OpenStreetMap layer.")
        val handle = layerHandles[0]!!
        println("Layer Name: ${handle.layerName}, Source Name: ${handle.sourceName}")
    } else {
        println("Failed to add OpenStreetMap layer.")
    }
}
```
