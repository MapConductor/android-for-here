# `rememberHereMapViewState`

A Jetpack Compose composable function that creates and remembers an instance of `HereViewState`.

## Signature

```kotlin
@Composable
fun rememberHereMapViewState(
    mapDesign: HereMapDesign = HereMapDesign.NormalDay,
    cameraPosition: MapCameraPositionInterface = MapCameraPosition.Default,
): HereViewState
```

## Description

This function is the recommended way to create and manage the state of a HERE map within a
composable UI. It automatically handles the persistence of the map's state (including camera
position and map style) across recompositions and configuration changes, such as screen rotation.
The returned `HereViewState` object serves as the single source of truth and the primary interface
for controlling the map programmatically.

## Parameters

- `mapDesign`
    - Type: `HereMapDesign`
    - Default: `HereMapDesign.NormalDay`
    - Description: The initial visual style and design of the map.
- `cameraPosition`
    - Type: `MapCameraPositionInterface`
    - Default: `MapCameraPosition.Default`
    - Description: The initial camera position, including target coordinates, zoom, tilt, and
                   bearing.
## Returns

- `HereViewState`
    - Type: `HereViewState`
    - Description: A remembered `HereViewState` instance that can be used to control the map.
## Example

The following example demonstrates how to create a `HereViewState` and use it to animate the map
camera to a new location when a button is clicked.

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.here.HereMapDesign
import com.mapconductor.here.rememberHereMapViewState
import kotlinx.coroutines.launch

@Composable
fun MyMapScreen() {
    // 1. Create and remember the map view state
    val mapViewState = rememberHereMapViewState(
        mapDesign = HereMapDesign.NormalDay,
    )

    val coroutineScope = rememberCoroutineScope()

    Column {
        // The HereMapView composable would take the state as a parameter
        // HereMapView(
        //     modifier = Modifier.weight(1f),
        //     state = mapViewState
        // )

        Button(onClick = {
            coroutineScope.launch {
                // 2. Use the state to control the map
                val newYork = GeoPoint(40.7128, -74.0060)
                mapViewState.moveCameraTo(
                    position = newYork,
                    durationMillis = 1000L // Animate over 1 second
                )
            }
        }) {
            Text("Go to New York")
        }
    }
}
```

---

# `HereViewState`

A state-holder class that manages the state and programmatic control of a HERE map view.

## Description

An instance of `HereViewState` represents the current state of the map, including its camera
position and design. It provides methods to manipulate the map's camera and properties to observe
its state. You typically obtain an instance of this class by calling the `rememberHereMapViewState`
composable.

## Properties

- `id`
    - Type: `String`
    - Description: A unique, stable identifier for the map state instance.
- `cameraPosition`
    - Type: `MapCameraPosition`
    - Description: (Read-only) The current position of the map's camera. This property is updated
                   automatically as the user interacts with the map.
- `mapDesignType`
    - Type: `HereMapDesignType`
    - Description: The current visual style of the map. Setting this property will update the map's
                   appearance in real-time.
## Methods

### `moveCameraTo(position: GeoPoint, ...)`

Moves the map camera to a new geographical coordinate, preserving the current zoom, tilt, and
bearing.

**Signature**
```kotlin
fun moveCameraTo(
    position: GeoPoint,
    durationMillis: Long? = null,
)
```

**Parameters**
- `position`
    - Type: `GeoPoint`
    - Description: The target geographical coordinate (`latitude`, `longitude`) to center the map
                   on.
- `durationMillis`
    - Type: `Long?`
    - Description: The duration of the camera animation in milliseconds. If `null` or `0`, the
                   camera moves instantly.
---

### `moveCameraTo(cameraPosition: MapCameraPosition, ...)`

Moves the map camera to a new, fully specified camera position, including target, zoom, tilt, and
bearing.

**Signature**
```kotlin
fun moveCameraTo(
    cameraPosition: MapCameraPosition,
    durationMillis: Long? = null,
)
```

**Parameters**
- `cameraPosition`
    - Type: `MapCameraPosition`
    - Description: The complete target camera position.
- `durationMillis`
    - Type: `Long?`
    - Description: The duration of the camera animation in milliseconds. If `null` or `0`, the
                   camera moves instantly.
