# HereMapDesign

## `HereMapDesign`

A sealed class that encapsulates the various map visual styles (schemes) available in the HERE SDK.
Each object within this class represents a specific `MapScheme` and provides a type-safe way to
manage and apply map designs.

This class implements the `HereMapDesignType` interface, which standardizes the handling of map
designs.

### Available Map Designs

The following singleton objects represent the available map designs.

- `NormalDay`
    - Description: The standard map style for daytime viewing.
- `NormalNight`
    - Description: The standard map style for nighttime viewing.
- `Satellite`
    - Description: A map style that displays satellite imagery.
- `HybridDay`
    - Description: Combines satellite imagery with road network and label overlays for daytime.
- `HybridNight`
    - Description: Combines satellite imagery with road network and label overlays for nighttime.
- `LiteDay`
    - Description: A lightweight, performance-optimized map style for daytime.
- `LiteNight`
    - Description: A lightweight, performance-optimized map style for nighttime.
- `LiteHybridDay`
    - Description: A lightweight hybrid map style for daytime.
- `LiteHybridNight`
    - Description: A lightweight hybrid map style for nighttime.
- `LogisticsDay`
    - Description: A map style optimized for logistics and trucking applications for daytime.
- `LogisticsNight`
    - Description: A map style optimized for logistics and trucking applications for nighttime.
- `LogisticsHybridDay`
    - Description: A hybrid map style optimized for logistics applications for daytime.
- `RoadNetworkDay`
    - Description: A map style that displays only the road network for daytime.
- `RoadNetworkNight`
    - Description: A map style that displays only the road network for nighttime.
### Methods

#### `getValue()`

Retrieves the underlying HERE SDK `MapScheme` enum associated with the `HereMapDesign` instance.

**Signature**
```kotlin
fun getValue(): MapScheme
```

**Returns**
- `MapScheme`
    - Type: `MapScheme`
    - Description: The corresponding `MapScheme` enum value.
---

## Companion Object

Provides factory methods to create `HereMapDesign` instances.

### `CreateById()`

Creates a `HereMapDesign` instance from its corresponding integer ID.

**Signature**
```kotlin
fun CreateById(id: Int): HereMapDesign
```

**Parameters**
- `id`
    - Type: `Int`
    - Description: The integer identifier of the map scheme, corresponding to `MapScheme.value`.
**Returns**
- `HereMapDesign`
    - Type: `HereMapDesign`
    - Description: The `HereMapDesign` object that corresponds to the given ID.
**Throws**
- `IllegalArgumentException`
### `Create()`

Creates a `HereMapDesign` instance from a `MapScheme` enum value.

**Signature**
```kotlin
fun Create(id: MapScheme): HereMapDesign
```

**Parameters**
- `id`
    - Type: `MapScheme`
    - Description: The `MapScheme` enum value to use.
**Returns**
- `HereMapDesign`
    - Type: `HereMapDesign`
    - Description: The `HereMapDesign` object that corresponds to the given `MapScheme`.
**Throws**
- `IllegalArgumentException`
---

## `HereMapDesignType`

A type alias for a generic map design interface, specialized for the HERE SDK.

**Signature**
```kotlin
typealias HereMapDesignType = MapDesignTypeInterface<MapScheme>
```

**Description**
This alias simplifies the use of `MapDesignTypeInterface` by fixing its generic type to `MapScheme`,
making it specific to the HERE map implementation.

---

## Example

The following examples demonstrate how to use `HereMapDesign` to set a map's visual style.

```kotlin
import com.here.sdk.mapview.MapView
import com.mapconductor.here.HereMapDesign

// Assume 'mapView' is an initialized instance of com.here.sdk.mapview.MapView

// --- Usage 1: Applying a map scheme directly ---
// Use one of the predefined objects to set the map scheme.
val dayDesign = HereMapDesign.NormalDay
mapView.mapScene.loadScene(dayDesign.getValue()) { mapError ->
    if (mapError == null) {
        println("Successfully loaded NormalDay scheme.")
    } else {
        println("Error loading scene: ${mapError.name}")
    }
}

// --- Usage 2: Creating a design from a MapScheme enum ---
// This is useful when you have a MapScheme from another part of your application.
val satelliteScheme = com.here.sdk.mapview.MapScheme.SATELLITE
val satelliteDesign = HereMapDesign.Create(satelliteScheme)
mapView.mapScene.loadScene(satelliteDesign.getValue(), null)


// --- Usage 3: Creating a design from an integer ID ---
// This can be useful for deserialization or when working with legacy code.
val schemeId = com.here.sdk.mapview.MapScheme.HYBRID_NIGHT.value
try {
    val hybridNightDesign = HereMapDesign.CreateById(schemeId)
    mapView.mapScene.loadScene(hybridNightDesign.getValue(), null)
    println("Successfully created design from ID: $schemeId")
} catch (e: IllegalArgumentException) {
    println("Error: ${e.message}")
}
```
